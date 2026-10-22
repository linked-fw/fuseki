---
summary: >
  The Fuseki module and image of CN plan 070 (step F1): `lf:DatasetRDFSLive` (Jena's query-time
  RDFS view over TDB2, its class hierarchy rebuilt lazily from the stored default-graph
  `rdfs:subClassOf` triples at the first read after a commit that touched one), the
  `/<ds>/stored` endpoint, SERVICE off, the inferring `config-tdb2` template and `ds` dataset,
  the delete filter and the template guard; measurements M1-M13 that settle the pending contract
  items (text index works over the type; backups and `GET /<ds>` include inferred triples; Fuseki
  leaves a wrapped dataset's files on delete); the `storedEndpoint` helper; image
  `ghcr.io/linked-fw/fuseki:6.2.0-1` (published at release).
packages: [fuseki]
---

# 001 — Store inference: the RDFS module, the image and the stored endpoints

Source: sub-plan [001](../plans/001-store-inference.md), CN plan 070 step F1 (sensitive).

## What was built

**The module** (`server/`, Java 21, Maven, Jena 6.2.0 provided; not in the npm package):

- `lf:DatasetRDFSLive` (`PREFIX lf: <https://linked.cm/ont/fuseki#>`), registered by a Jena
  subsystem (`InitLinkedFuseki`, level 500) so configuration files and the admin template can
  name it and `lf:stored` before they are parsed. `DatasetGraphRDFSLive` wraps the stored dataset
  (TDB2's `DatasetGraphSwitchable`) with Jena's `DatasetGraphRDFS`, built from
  `Hierarchy.read`: the default graph's `rdfs:subClassOf` triples only (decision 3).
- **Rebuild.** `SubClassOfTracker` (a dataset wrapper, plus `TrackingGraph` for graph handles,
  which TDB2 writes through directly) marks the writing thread when a write may touch a
  default-graph `subClassOf` triple (wildcard deletes and `clear` included). `commit` bumps a
  generation counter after TDB2's commit returns; it never reads or rebuilds. A transaction
  records the generation **before** it begins; at its first read it takes the current view, or,
  when that is older, rebuilds once inside itself under a lock (others wait and re-check). Writes
  go straight to the tracker and never resolve a view, so write-only transactions never rebuild.
  A write transaction that already changed the hierarchy and then reads builds a private view
  that is never published (its triples are uncommitted). Each transaction keeps its view until
  `end`.
- **`lf:stored`**: one operation (`StoredReadService`) that dispatches to `StoredQuery`
  (`SPARQL_QueryDataset` over the raw TDB2 dataset: TDB2's own engine, the action's transaction)
  or `StoredGSP` (`GSP_R`, writes 405) by the request: a `query` parameter, an
  `application/sparql-query` body or the QUERY method is a query, anything else a GSP read.
  `LiveDatasets` unwraps through `text:TextDataset`.
- **`FMod_LinkedFuseki`** (Fuseki auto-module): `arq:httpServiceAllowed=false`; the `lf:stored`
  handler; `TemplateGuard` (rewrites `$FUSEKI_BASE/templates/config-tdb2` when missing or
  different, WARN when it replaced one); `DropDatabaseFilter` on `/$/datasets/*` (after a 2xx
  `DELETE` of a live dataset, removes `<run area>/databases/<name>` when that is exactly the TDB2
  location and not a symbolic link; `-Dlf.dropFilter=false` turns it off, for M4).
- **JUnit** (43 tests, `mvn -B -f server verify`): `DatasetGraphRDFSLiveTest` (inference in the
  default, named and union graphs; stored dataset free of inferred triples; write paths INSERT
  DATA, DELETE DATA, DELETE WHERE, INSERT WHERE, CLEAR DEFAULT, CLEAR ALL, DROP ALL, LOAD,
  graph-level add/delete/remove/clear as GSP does, quads read into the dataset, `addGraph`; no
  rebuild for other predicates or a named-graph `subClassOf`; aborted and un-committed writes;
  800 commits → exactly one rebuild, the query sees all 800; 8 concurrent readers → one rebuild;
  a reader pinned across a hierarchy commit; a writer sees its change from its next transaction;
  a writer blocked on the writer lock reads with the hierarchy it waited for; read-committed
  promotion; whole-dataset clear, ADD/COPY/MOVE into the default graph;
  a cycle; `subPropertyOf`/`domain`/`range` infer nothing; deleting an inferred triple is a no-op),
  `AssemblerTest` (assembler, persistence of a named graph and its inference across close, expel
  and reopen; missing `ja:dataset`; `text:TextDataset` over the type), `FusekiStoredTest`
  (embedded server from the template: SPARQL infers; `/stored` query forms and GSP reads hold no
  inferred triple; writes to `/stored` 405; `GET /<ds>` holds inferred quads; SERVICE 422; GSP
  PUT/POST/DELETE on `?default`; quads POST), `AdminTemplateTest` (run area with the stock
  template: replaced; a form-created dataset infers; the delete removes the files; a re-created
  name is empty; an unmanaged live dataset keeps its files; a failed delete), `TemplateGuardTest`,
  `DropDatabaseFilterTest`.

**The image** (`server/Dockerfile`): `secoresearch/fuseki:6.2.0` pinned by digest, the jar in
`/javalibs/`, `templates/config-tdb2` (the stock template with the dataset swapped and `stored` and
`shacl` endpoints added, no `unionDefaultGraph`), `configuration/assembler.ttl` (`ds`: the base
image's service lines byte for byte, plus `stored`; `text:TextDataset` → `lf:DatasetRDFSLive` →
TDB2 at `/fuseki-base/databases/ds`). `server/IMAGE_TAG` `6.2.0-1` equals the pom version.
`server/scripts/smoke.sh` (18 checks, ~4 s). Workflows: `server.yml` (on push touching `server/`:
version check, `mvn verify`, image build, smoke; not a required check) and `image.yml` (on `main`
touching `server/`, or dispatch: reads the existing tags through the Packages API (404: not
published yet; any other error stops the job) and skips an existing tag, verifies, builds amd64 and smokes, then
pushes amd64+arm64 to `ghcr.io/linked-fw/fuseki:<IMAGE_TAG>`).

**The measurement rig** (`server/probe/`: `compose.yml`, `probe.sh`, `text.ttl`, `plain.ttl`)
and **the benchmark** (`server/bench/`: `gen.py` taking the schema data directory as an argument,
`bench.mjs` with `BENCH_URL`/`BENCH_SERVICES`, `conf/bench.ttl` with the three services over one
TDB2 database).

**The TS package**: `storedEndpoint(base, dataset)` in `src/utils/datasets.ts` (exported from the
root) with `src/utils/datasets.test.ts`; `README.md` rewritten (store, the image, inference, `ds`,
stored reads, graphs, backups and deletion, SERVICE, Fuseki 6.2 notes, building, releasing);
`lincd-fuseki.README.md` points to it; changeset `stored-endpoint-and-image.md` (minor).

## Measurements (M1-M13)

On the built image `linked-fuseki:dev` (module `6.2.0-1`), compose project `f1-probe`, ports
3234/3235/3232, never 3030. `LF_BENCH_DATA=<ideation data> server/probe/probe.sh`, output as
printed. This is the run after the review fixes (2026-10-21, total 4:22); the M1-M13 lines of
the first run (before them) were the same apart from timings:

```
image: linked-fuseki:dev; module: [linked-fuseki] module 6.2.0-1: lf:DatasetRDFSLive
M1 template dataset infers: create 200; /m1/sparql ASK a⊑B true; /m1/stored false; endpoints gsp-r='','get' gsp-rw='','data' query='query','sparql','' shacl='shacl' stored='stored' update='update',''
M2 stored forms: GET quads 3 (stored 3); ?default 2; ?graph= 1; ?query= ASK stored true; POST sparql-query ASK inferred false; POST form query= true; POST GSP 405; PUT GSP 405; sparql-update POST 405
M3 $/backup: task 1, file m1_2026-10-21_20-48-15.nq.gz, 5 quads (stored 3), inferred 'a a B' lines: 1
M4 delete (module filter on): DELETE 200; files gone; re-created m1 stored quads 0
M4 delete (filter off, Fuseki alone): DELETE 200; files present; re-created m4 stored quads 2
M5 text:TextDataset over lf:DatasetRDFSLive: text:query finds true; text hit a superclass true; /stored inferred false
M6 compact: status 200 task 1; stored quads after 2 (expect 2); ASK a⊑B true; write after compact 204 then ASK true
M7 restart: stored quads 2 -> 2; named-graph inference true; log: [linked-fuseki] /fuseki-base/databases/m7/: hierarchy built (0 subClassOf, 0 ms) [linked-fuseki] /fuseki-base/databases/m7/: hierarchy built (1 subClassOf, 1 ms) 
M8 SERVICE: status 422; log: [3] 422 Unprocessable Entity (4 ms)
M9 lazy rebuild: 800 commits 42236 ms on the type vs 43563 ms plain TDB2; rebuilds during the burst 0; first ASK true in 121 ms; rebuilds after it 1; rebuilt (800 subClassOf, 19 ms, generation 800)
M11 copy round trip: POST 200; stored quads src 5 dst 5; inferred dst a⊑B true g:b⊑B true
M12a ds (ENABLE_*=true): infers true; /ds/stored false; text true; shacl report focus :a 1; db dir ds lucene m11dst m11src m6 m7 m9 plain textprobe textprobe-lucene 
M12b ds (ENABLE_* unset): update 405; shacl 405; data PUT 405; data GET 200; sparql false
M13 template guard: stock file mentions the type 0 -> after start 2; WARN lines 1; form-created dataset infers true
```

M10 in that run was taken while the machine was under heavy load from other agents (plain TDB2's
Q1 took 31 ms instead of 6), so the benchmark tables below are the first run's (module before the
review fixes; the fixes change only what happens at a transaction's first read when the shared
view is older, not the read path). The rerun's hierarchy change: `H1` first query after the insert
10.2 ms, true; rebuilds 6 ms and 5 ms; hierarchy built at open in 78 ms.

M10, the ~1M-triple benchmark (951,284 triples: 850,470 default-graph incl. schema.org's 952
`subClassOf`, 99,862 in a named graph; loaded with `tdb2.tdbloader`). `bench` is
`lf:DatasetRDFSLive`, `bench-stock` Jena's `ja:DatasetRDFS` (fixed schema, decision 9's
configuration), `bench-plain` the TDB2 dataset; one cold run then the median and p90 of 10, ms:

```
M10 bench: load 23345 ms; start 5079 ms; [linked-fuseki] /bench/db/main/: hierarchy built (952 subClassOf, 81 ms)
[linked-fuseki] /fuseki-base/databases/ds/: hierarchy built (0 subClassOf, 0 ms)
M10 Q1 count Thing	bench	cold	1264.5	med	233.6	p90	275.0	c=141546
M10 Q1 count Thing	bench-stock	cold	192.4	med	197.3	p90	253.4	c=141546
M10 Q1 count Thing	bench-plain	cold	24.2	med	6.3	p90	8.5	c=0
M10 Q2a count CreativeWork	bench	cold	158.3	med	163.7	p90	344.1	c=87502
M10 Q2a count CreativeWork	bench-stock	cold	109.6	med	109.3	p90	143.8	c=87502
M10 Q2a count CreativeWork	bench-plain	cold	4.4	med	5.8	p90	7.8	c=0
M10 Q2b count Photograph	bench	cold	8.2	med	11.1	p90	17.6	c=1662
M10 Q2b count Photograph	bench-stock	cold	20.6	med	13.4	p90	35.3	c=1662
M10 Q2b count Photograph	bench-plain	cold	28.2	med	14.1	p90	16.9	c=1662
M10 Q3a page CW off 0	bench	cold	1409.7	med	588.7	p90	817.8	rows=50
M10 Q3a page CW off 0	bench-stock	cold	459.0	med	439.8	p90	474.4	rows=50
M10 Q3a page CW off 0	bench-plain	cold	6.2	med	7.8	p90	9.3	rows=0
M10 Q3b page CW off 5000	bench	cold	907.7	med	900.6	p90	948.8	rows=50
M10 Q3b page CW off 5000	bench-stock	cold	850.7	med	855.8	p90	948.4	rows=50
M10 Q3b page CW off 5000	bench-plain	cold	6.5	med	4.8	p90	10.7	rows=0
M10 Q4 Event select	bench	cold	32.8	med	24.1	p90	24.3	rows=100
M10 Q4 Event select	bench-stock	cold	21.6	med	22.7	p90	26.2	rows=100
M10 Q4 Event select	bench-plain	cold	20.2	med	12.9	p90	14.9	rows=100
M10 Q5 ASK inst a Thing	bench	cold	6.7	med	4.9	p90	7.1	true
M10 Q5 ASK inst a Thing	bench-stock	cold	6.5	med	4.8	p90	7.4	true
M10 Q5 ASK inst a Thing	bench-plain	cold	6.5	med	4.6	p90	5.0	false
M10 Q6 inst ?p ?o	bench	cold	14.2	med	6.3	p90	6.9	rows=11
M10 Q6 inst ?p ?o	bench-stock	cold	14.0	med	6.0	p90	8.0	rows=11
M10 Q6 inst ?p ?o	bench-plain	cold	5.1	med	5.2	p90	5.6	rows=9
M10 Q7 owned walk	bench	cold	91.5	med	87.3	p90	104.7	rows=1000
M10 Q7 owned walk	bench-stock	cold	86.6	med	82.5	p90	103.0	rows=1000
M10 Q7 owned walk	bench-plain	cold	15.6	med	5.3	p90	9.0	rows=0
M10 Q9a GRAPH ?g Thing	bench	cold	181.3	med	41.9	p90	45.9	c=16629
M10 Q9a GRAPH ?g Thing	bench-stock	cold	29.9	med	36.4	p90	41.0	c=16629
M10 Q9a GRAPH ?g Thing	bench-plain	cold	8.5	med	4.5	p90	5.9	c=0
M10 Q8 INSERT DATA	bench	cold	46.5	med	10.7	p90	18.2
M10 Q8 DELETE DATA	bench	cold	16.8	med	10.7	p90	15.9
M10 Q8 INSERT DATA	bench-stock	cold	9.8	med	10.0	p90	12.6
M10 Q8 DELETE DATA	bench-stock	cold	12.7	med	9.9	p90	10.6
M10 Q8 INSERT DATA	bench-plain	cold	7.6	med	9.0	p90	10.5
M10 Q8 DELETE DATA	bench-plain	cold	8.6	med	9.2	p90	10.9
M10 H1 after subClassOf insert: ASK new instance a Thing	bench	first query ms	16.5	true
M10 H2 count Thing after the change	bench	ms	292.8	c=141547
M10 H3 after delete: ASK	bench	first query ms	8.9	false
M10 rebuild log: [linked-fuseki] /bench/db/main/: hierarchy rebuilt (953 subClassOf, 9 ms, generation 1) [linked-fuseki] /bench/db/main/: hierarchy rebuilt (952 subClassOf, 4 ms, generation 2) 
```

A second run (`LF_ONLY_BENCH=1 LF_BENCH_REVERSED=1`) with the service order reversed, to separate
order and noise from the type's cost (the machine was running other agents' builds and tests):

```
M10r Q1 count Thing	bench-plain	cold	97.5	med	9.0	p90	13.0	c=0
M10r Q1 count Thing	bench-stock	cold	333.7	med	321.0	p90	464.3	c=141546
M10r Q1 count Thing	bench	cold	374.8	med	263.8	p90	385.7	c=141546
M10r Q2a count CreativeWork	bench-plain	cold	6.6	med	5.5	p90	12.5	c=0
M10r Q2a count CreativeWork	bench-stock	cold	221.6	med	159.6	p90	471.3	c=87502
M10r Q2a count CreativeWork	bench	cold	115.1	med	149.2	p90	168.1	c=87502
M10r Q2b count Photograph	bench-plain	cold	6.6	med	7.1	p90	8.8	c=1662
M10r Q2b count Photograph	bench-stock	cold	6.1	med	8.5	p90	10.5	c=1662
M10r Q2b count Photograph	bench	cold	7.4	med	7.0	p90	10.3	c=1662
M10r Q3a page CW off 0	bench-plain	cold	9.1	med	6.3	p90	6.7	rows=0
M10r Q3a page CW off 0	bench-stock	cold	544.8	med	513.0	p90	696.8	rows=50
M10r Q3a page CW off 0	bench	cold	435.2	med	585.4	p90	663.1	rows=50
M10r Q3b page CW off 5000	bench-plain	cold	20.2	med	14.0	p90	15.4	rows=0
M10r Q3b page CW off 5000	bench-stock	cold	1063.5	med	1016.3	p90	1119.7	rows=50
M10r Q3b page CW off 5000	bench	cold	983.9	med	982.3	p90	1307.9	rows=50
M10r Q4 Event select	bench-plain	cold	24.9	med	15.3	p90	16.3	rows=100
M10r Q4 Event select	bench-stock	cold	25.7	med	15.9	p90	22.1	rows=100
M10r Q4 Event select	bench	cold	15.8	med	19.3	p90	22.2	rows=100
M10r Q5 ASK inst a Thing	bench-plain	cold	13.0	med	8.2	p90	9.5	false
M10r Q5 ASK inst a Thing	bench-stock	cold	6.0	med	6.8	p90	9.0	true
M10r Q5 ASK inst a Thing	bench	cold	8.6	med	6.1	p90	6.9	true
M10r Q6 inst ?p ?o	bench-plain	cold	5.2	med	5.7	p90	8.7	rows=9
M10r Q6 inst ?p ?o	bench-stock	cold	5.2	med	7.0	p90	10.3	rows=11
M10r Q6 inst ?p ?o	bench	cold	8.4	med	7.5	p90	9.2	rows=11
M10r Q7 owned walk	bench-plain	cold	9.9	med	8.4	p90	12.6	rows=0
M10r Q7 owned walk	bench-stock	cold	126.5	med	79.2	p90	117.4	rows=1000
M10r Q7 owned walk	bench	cold	66.5	med	70.0	p90	72.6	rows=1000
M10r Q9a GRAPH ?g Thing	bench-plain	cold	5.5	med	7.3	p90	8.5	c=0
M10r Q9a GRAPH ?g Thing	bench-stock	cold	28.0	med	25.5	p90	27.5	c=16629
M10r Q9a GRAPH ?g Thing	bench	cold	26.0	med	19.8	p90	27.5	c=16629
M10r Q8 INSERT DATA	bench-plain	cold	15.8	med	8.2	p90	12.2
M10r Q8 DELETE DATA	bench-plain	cold	9.6	med	8.8	p90	10.1
M10r Q8 INSERT DATA	bench-stock	cold	8.3	med	7.7	p90	7.8
M10r Q8 DELETE DATA	bench-stock	cold	7.7	med	8.2	p90	10.0
M10r Q8 INSERT DATA	bench	cold	7.1	med	9.3	p90	10.7
M10r Q8 DELETE DATA	bench	cold	6.9	med	9.0	p90	12.6
```

**Reading M10.** The live type answers what stock `ja:DatasetRDFS` answers (same counts and rows)
at the same cost within this machine's noise: per query the live median is within ±20 % of the
stock median of the same pass in the reversed run (Q1 264 vs 321, Q2a 149 vs 160, Q3a 585 vs 513
(+14 %), Q4 19 vs 16 (+3 ms), Q7 70 vs 79, Q9a 20 vs 26), while the first pass, where the live
service always ran first per query, put it up to 50 % behind (Q2a 164 vs 109) and the forward
pass of the second run put it ahead (Q2a 169 vs 333). Against decision 9's ideation numbers
(stock: Q1 274, Q2a 137, Q3a 516, Q3b 1039, Q4 20, Q7 90, Q9a 33) the live medians of the first
run are 234, 164, 589, 901, 24, 87, 42. Writes cost the same (INSERT/DELETE DATA ~10 ms
median on all three). A hierarchy change mid-run: the first query after inserting
`ex:NewKind ⊑ schema:Thing` sees the new instance as a `Thing` (16.5 ms including the rebuild,
which took 9 ms for 953 axioms); the delete is seen the same way (rebuild 4 ms). Opening the
dataset builds the hierarchy in 81 ms (952 axioms); startup 5 s.

## Settled contract items and final contract text

Every *pending F1* item and the measured additions. Text to replace or add in CN plan 070
§Contracts (§The image) and the copy in sub-plan 001:

1. **Text index (was *pending F1*)** — M5, the `ds` dataset (M12a, smoke) and `AssemblerTest`:
   > `text:TextDataset` works over `lf:DatasetRDFSLive` (`text:dataset` → the live type → TDB2), as
   > pg-test declares it: `text:query` finds subclass instances, which also answer superclass
   > patterns; `/stored` unwraps through the text dataset.
2. **Lazy rebuild — wording changes** (the measured behaviour differs from "the first transaction
   to begin afterwards rebuilds it"): with a rebuild at begin, each of K1's 800 sync commits is a
   write transaction that begins after the previous commit, so it would rebuild 799 times
   (measured in JUnit before the change), contradicting "a burst of sync commits costs one
   rebuild". The view is resolved at a transaction's first read instead (M9: 0 rebuilds during
   800 commits, 1 at the next read, 19 ms). Proposed text:
   > It infers over every graph; its hierarchy is the closure of the stored `rdfs:subClassOf`
   > triples in the default graph. A commit that adds or removes one marks the hierarchy stale;
   > the first transaction that reads afterwards rebuilds it once, inside that transaction (other
   > readers wait for it), and each transaction keeps the hierarchy it first read with until it
   > ends. Write-only transactions never rebuild, so a burst of sync commits costs one rebuild, at
   > the next read. A transaction never reads with a hierarchy older than its snapshot (one that
   > waited for the writer lock or began during a commit builds its own); it may read with a newer
   > one when another transaction rebuilt after it began. A writer sees its own `subClassOf` change
   > from its next transaction (within the same transaction only when the shared hierarchy was
   > already stale at its first read). Opening a dataset (start, restart) builds its hierarchy
   > once. Only writes through the dataset mark the hierarchy: a second service or assembler over
   > the same TDB2 location does not.
3. **Backups** — M3: `$/backup/m1` wrote 5 quads for 3 stored, the inferred `ex:a a ex:B` among
   them. The adopted text stands unchanged ("`POST $/backup` and `GET /<ds>` dump the service's
   dataset, inferred triples included, so they are never used for these datasets"); add `GET
   /<ds>/data` (GSP on the service, smoke: 3 triples for 2 stored) to the list.
4. **Delete** — M4: Fuseki alone answers 200, leaves `/fuseki-base/databases/m4` on disk, and a
   re-created `m4` holds the old 2 quads; with the module the files are gone and the re-created
   dataset is empty. Narrowed after review (Fuseki keeps the files of datasets declared in a
   shared configuration file, and so must the module). Proposed text:
   > `DELETE $/datasets/<ds>` removes the TDB2 files of a dataset created through the admin API
   > (its own `configuration/<ds>.ttl`) under `/fuseki-base/databases/` (the module completes what
   > Fuseki skips for wrapped datasets); a re-created name starts empty. Datasets declared in a
   > shared configuration file (`ds`, cn-main, cn-ai, pg-test) are only unregistered, as on plain
   > Fuseki.
5. **One stored endpoint** — M2: whole dataset 3 quads, `?default` 2, `?graph=` 1, `?query=`,
   `POST application/sparql-query` and form `query=` answer from stored triples only; GSP POST and
   PUT and a SPARQL update POST answer 405. The adopted text stands.
6. **Compact** — M6: `$/compact/<ds>?deleteOld=true` 200, data and inference intact, writes work
   after it. Add: "`$/compact` works as on plain TDB2."
7. **SERVICE** — M8: 422 Unprocessable Entity. The adopted text stands.
8. **Template guard** — M13: a bind-mounted stock `config-tdb2` is rewritten at start with one
   WARN; a form-created dataset then infers. The adopted text stands.
9. **`ds`** — M12: with `ENABLE_*=true` it infers, `/ds/stored` is stored-only, the text index
   and `/ds/shacl` (a shape targeting the superclass reports the subclass instance) work; without
   them `update`, `shacl` and a GSP PUT answer 405 and the read-only `data` answers 200. The
   adopted text stands.
10. **New: `upload` does not exist on Fuseki 6.2.** `/<ds>/upload` answers 405 with "No Fuseki
    dispatch" on a form-created dataset of the stock `secoresearch/fuseki:6.2.0` as on the image:
    Fuseki 6's upload handler belongs to its UI module, which the server command does not load.
    The template keeps the stock `fuseki:serviceUpload "upload"` line (harmless). CN code does not
    call `/upload` (searched `src`, `scripts`, `packages`, `tests`); pg-test's assembler declares
    it. Proposed addition to §The image: "`/<ds>/upload` is not served on Fuseki 6.2 (405); load
    files through `/<ds>/data` (GSP) or N-Quads to `/<ds>`." Consumer: C1.

## Verify and gates

Full gate before landing, after the review fixes (2026-10-21), output as printed:

```
$ mvn -B -f server verify
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.452 s -- in cm.linked.fuseki.AdminTemplateTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.708 s -- in cm.linked.fuseki.FusekiStoredTest
[INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.442 s -- in cm.linked.fuseki.DatasetGraphRDFSLiveTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.085 s -- in cm.linked.fuseki.TemplateGuardTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.173 s -- in cm.linked.fuseki.AssemblerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.781 s -- in cm.linked.fuseki.fuseki.DropDatabaseFilterTest
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  18.880 s
( mvn -B -f server verify 2>&1 | grep -E 'Tests run:|BUILD|Total time'; )  22.26s user 2.15s system 116% cpu 20.897 total
$ docker build -t linked-fuseki:dev server
sha256:e7caffc3922fb90586cbb1749698864d7b6ed7edb5b74ab74bba90fd0fa1d1e6
( docker build -q -t linked-fuseki:dev server; )  0.17s user 0.27s system 13% cpu 3.183 total
$ server/scripts/smoke.sh linked-fuseki:dev
ok   server up
ok   POST $/datasets dbType=tdb2 (200)
ok   template dataset infers (/smoke/sparql) (true)
ok   named graph inferred (true)
ok   /smoke/stored holds no inferred triple (false)
ok   GET /smoke/stored quads = inserted (3)
ok   GET /smoke/data?default includes inferred triples (3)
ok   quads POST to the dataset URL (200)
ok   the POST changed the hierarchy (true)
ok   SERVICE refused (422)
ok   ds infers (true)
ok   ds /stored stored-only (false)
ok   ds data read-write (ENABLE_DATA_WRITE) (200)
ok   ds text index (true)
ok   ds shacl: a shape on the superclass reports the subclass instance
ok   DELETE $/datasets/smoke (200)
ok   database directory removed
smoke: 5 s
server/scripts/smoke.sh linked-fuseki:dev  0.15s user 0.20s system 6% cpu 5.104 total
$ npx linked build && npm test
✔ Build successful
Test Suites: 3 passed, 3 total
Tests:       12 passed, 12 total
( npx linked build 2>&1 | tail -1 && npm test 2>&1 | grep -E 'Tests:|Suites:')  13.86s user 1.79s system 219% cpu 7.122 total
$ npm pack --dry-run 2>&1 | grep -c 'server/'
0
```

The probe (`server/probe/probe.sh`) ran on the image after the last module change (above, 3:26).
Quick gate after phase 1: `mvn -B -f server verify` 38 tests, BUILD SUCCESS, 17.1 s; after phase 2
the same plus `docker build` (1:45 cold) and `smoke.sh` (16 ok, 4 s).

## Deviations from the sub-plan

- **Rebuild at first read, not at begin** (contract item 2 above): needed for "a burst of
  commits costs one rebuild". `getW()` goes to the tracker, so writes never resolve a view; a
  view built by a writer after its own hierarchy change stays private.
- **Two counters instead of one generation read at begin.** A label read after `begin` can count
  a commit that finished after the transaction's snapshot (the view would carry a label newer
  than its data and never be rebuilt); a label read before `begin` alone (the first version) let a
  writer that waited for TDB2's writer lock read with the hierarchy from before the commit it
  waited for (review finding 1). A transaction now records the finished hierarchy commits before
  `begin` (the label it may publish) and the started ones after it (what its snapshot may
  contain); when the shared view is older than the latter it builds from its own snapshot.
  Read-committed promotion resolves again.
- **The tracker's mark is per thread**, not a dataset-wide flag: with one flag, a writer that
  begins while the previous writer is still in `end()` (TDB2 releases the writer lock at commit)
  could have its mark reset.
- **Hierarchy at open, not first transaction** (M7): the dataset builds generation 0 when it is
  opened (log `hierarchy built`), so a restart costs no rebuild.
- **Upload is not tested in JUnit or the smoke** (contract item 10); the smoke posts N-Quads to
  the dataset URL instead, the other bulk path.
- **Compose project `f1-probe`** (the step's instruction) instead of `lf-f1-probe`; smoke on
  3233, probe on 3234 and 3235, bench on 3232.
- **The delete filter acts on managed datasets only** (its own configuration file, removed by the
  delete; name unregistered; under the server lock), review finding 2.
- **`TemplateGuard`** falls back to an existing `run/` when `FUSEKI_BASE` is unset and logs an
  ERROR instead of stopping start when it cannot write the template.
- `DropDatabaseFilter` compares the location's parent by real path (macOS temporary directories
  are reached through `/var` → `/private/var`); the database directory itself must still not be a
  link.

## Follow-ups

- **Publish the image** (`ghcr.io/linked-fw/fuseki:6.2.0-1`), set the GHCR package public once
  and link it to the repository: CN plan 070 step R (owner), recorded in this README's
  "Releasing the image".
- **The two workflows run first on push of `store-inference`** (`server.yml`); `image.yml` only on
  `main`, so it is first exercised at R.
- **pg-test's `fuseki:serviceUpload`**, and any consumer that relied on `/upload`: CN plan 070
  step C1 (§Contracts item from this report).
- **Contract text items 1, 2, 4, 6 and 10** handed to the master for §Contracts and sub-plan 001.

## Independent review

An independent read-only reviewer (opus) reviewed the diff against plan 070 §F1, §Contracts and
sub-plan 001, then each round of fixes.

- **Round 1** (`13b9cb4..25715a5`): "Verdict: 3 open medium/high findings (2 high, 1 medium)."
  - High: a writer that waited for TDB2's writer lock read with the hierarchy from before the
    commit it waited for (reproduced). Fixed with the two counters (Deviations), plus a test that
    fails without the fix.
  - High: the delete filter removed the files of unmanaged datasets (`ds`, and cn-main, cn-ai,
    pg-test once in `assembler.ttl`). Fixed: managed datasets only, re-checked under the server
    lock; test `anUnmanagedDatasetKeepsItsFiles`.
  - Medium: `image.yml` could overwrite a published tag (an unauthenticated inspect failure read
    as "absent"). Fixed.
  - Lows: `TemplateGuard` fallback and no abort; `ENABLE_DATA_WRITE` smoke check; nits. The rest
    are documented in the README: a newer hierarchy than the snapshot; a writer seeing its own
    change; writes that bypass the dataset; Lucene committed after TDB2; `LOAD <url>`; `/stored`
    below access control.
- **Round 2** (`25715a5..324f3ca`): findings 1, 2 and 6 confirmed fixed ("blocked writer sees
  stored A<=B: true; infers x a B: true"). New medium: the tag check's absent patterns did not
  match what the registry says. Measured: GHCR answers `denied` for a missing package and tag.
  Fixed (c422a41): existence comes from the Packages API (404 = absent).
- **Round 3** (c422a41): "Verdict: No open medium or high findings." One low remains: the API
  also answers 404 for a package the token may not see. The README's "Releasing the image" says to
  keep the package linked with Actions access.
- **Open lows, accepted:**
  - If Fuseki's `dataService.shutdown()` throws after the configuration is removed, the filter may
    delete the files of a store that is still open (rare).
  - Without `FUSEKI_BASE`, on the first start of a new `run/`, the template is not guarded (the
    image always sets `FUSEKI_BASE`).

## Addendum: GSP writes keep the no-rebuild guarantee (CN plan 070, iteration 1)

CN plan 070's review found that a GSP `POST`/`PUT`/`DELETE` carrying `rdfs:subClassOf` resolved
the inferred view inside its write transaction (`getG()` handed out the view's graphs), so N such
writes cost N rebuilds, against "write-only transactions never rebuild".

- **Fix.** In a write transaction that has not read yet, `getR()`/`getG()` return
  `WriteTxnDataset`: the tracker, whose graph handles (`LazyGraph`) write to the tracked graphs
  and resolve the view only on `find`/`contains`/`stream`. Emptiness, sizes, graph names and
  `containsGraph` come from the store, as Jena's view answers them too. `containsGraph` of the
  live dataset always asks the tracker.
- **Tests.** `FusekiStoredTest.gspPostRebuildsAtTheNextReadOnly`, `gspPutRebuildsAtTheNextReadOnly`,
  `gspDeleteRebuildsAtTheNextReadOnly`: two or three GSP writes, then `rebuilds()` unchanged, then
  the next read sees the new hierarchy with exactly one rebuild. Before the fix they failed:
  `no rebuild inside a GSP POST ==> expected: <15> but was: <17>` (PUT 9/11, DELETE 19/21).
  `addCopyMoveReadTheSourceThroughTheView` pins the README note that `ADD`/`COPY`/`MOVE` store the
  source's inferred triples.
- **Image probe** (port 3244, own container): three GSP `POST`s and a `PUT` on `?default` with
  `subClassOf`, then `rebuilds before read: 0`; the next `ASK` answered `true` with
  `hierarchy rebuilt (1 subClassOf, 0 ms, generation 4)`, the only rebuild line.
- **Also:** `image.yml` publishes only on `refs/heads/main` (job `if`), concurrency group
  `image-publish` on the job; README notes for `ADD`/`COPY`/`MOVE` and `FUSEKI_DEFAULT_GRAPH`; the
  changeset lists the image's behaviour changes (minor); `TemplateGuard`'s comment names
  `Lib.getenv`'s order (it already matched); the unfixed review lows are
  [backlog 001](../backlog/001-store-inference-review-lows.md).

**Gate** (after rebasing onto `store-inference` 6d4dfac):

```
mvn -B -f server verify        Tests run: 47, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS   real 17.96
  FusekiStoredTest             Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.608 s
  DatasetGraphRDFSLiveTest     Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.357 s
docker build server            real 5.58
smoke.sh (SMOKE_PORT=3243)     17 ok, 0 FAIL   smoke: 4 s
linked build (integration CLI) ✔ Build successful   real 3.42
npm test                       Test Suites: 3 passed, 3 total   Tests: 12 passed, 12 total   real 2.40
```

**Independent review** (opus, read-only) of the diff against plan 070 gap 1 and §Contracts §The
image. Round 1: five lows (a non-main dispatch could cancel a queued main publish through the
workflow-level group; backlog §1 understated the auto-promotion case; dataset `stream()` does not
infer, pre-existing; `LazyGraph.size()` resolved the view needlessly; the tests miss private
builds and `MOVE`), "Verdict: No open medium or high findings." All five taken up (fixed, or a
backlog line). Round 2: "All five lows from round 1 are fixed, and nothing new appears at medium
or high." — "Verdict: No open medium or high findings."
