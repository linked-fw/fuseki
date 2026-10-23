---
summary: >
  The Fuseki module of CN plan 071 (step F1): `lf:DatasetRDFSLive` builds its class hierarchy
  from the default graph plus every graph the graph registry
  (`https://linked.cm/pkg/core/graph/registry`) lists with `core:feedsHierarchy true`, and marks
  it stale on registry writes and on `subClassOf` writes to the default or a listed graph (graph
  handles and GSP included); a stale rule closes the race between a registry commit and the
  published graph set. Image `6.2.0-2` (built locally as `linked-fuseki:071`); 63 JUnit tests,
  24 smoke checks; the 1M-triple bench with 20 listed graphs (rebuild 6-43 ms, open 102 ms).
packages: [fuseki]
---

# 002 — Named graphs: the hierarchy from the graph registry

Record of step F1 of CN plan 071 (sub-plan [002](../plans/002-named-graphs.md)). Integration
branch `named-graphs`; worktree `~/web/wt/f1` (branch `named-graphs--f1`).

## What was built

- **`Hierarchy`** (`server/src/main/java/cm/linked/fuseki/rdfs/Hierarchy.java`): constants
  `REGISTRY`, `FEEDS_HIERARCHY`, `SD_NAME`; `read(stored)` returns the vocabulary and the listed
  graphs: every `?r core:feedsHierarchy ?f` in the registry with `?f` an `xsd:boolean` true
  (compared by value: TDB2 inlines booleans), then its IRI `sd:name` values (the default and union
  graph names are skipped), then the `subClassOf` triples of the default graph and of each listed
  graph. Read on the stored dataset, never through the view.
- **`SubClassOfTracker`**: `isHierarchyGraph(g)` (wildcard, default, union, a graph in the last
  published set, or any named graph while the set is stale) replaces `isDefaultOrAny`;
  `isRegistryOrAny(g)`; marks per thread with a registry bit; `published(set, generation)`;
  `registryCommitted(n)`. Every graph handle is a `TrackingGraph`.
- **`TrackingGraph`** holds its graph name and asks the tracker per write.
- **`DatasetGraphRDFSLive`**: `View` carries the listed graphs and its build time; the constructor
  and each published rebuild publish the set; a commit with the registry bit records its `pending`
  number before TDB2's commit releases the writer lock; `listedGraphs()`; log lines
  `hierarchy built (<n> subClassOf, <k> graphs, <ms> ms)` and
  `hierarchy rebuilt (<n> subClassOf, <k> graphs, <ms> ms, generation <g>)`.
- **Tests:** `DatasetGraphRDFSLiveTest` 37 (14 new), `AssemblerTest` +1, `FusekiStoredTest` +2;
  smoke +7 checks; bench variant `bench-listed` (`gen.py` writes `listed.nq` with the hierarchy
  split over 20 listed graphs and `instances-only.nt`; `probe.sh` loads it; `bench.mjs`
  `BENCH_HSVC`/`BENCH_HGRAPH`).
- `server/IMAGE_TAG` and `server/pom.xml` `6.2.0-2`; README §Datasets infer classes and §The graph
  registry; plan 001 marked as superseded for its graph text.
- No npm change: no changeset (F2 adds the minor one).

## Verify and gates

`mvn -B -f server verify` (final tree, 19.8 s):

```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.542 s -- in cm.linked.fuseki.AdminTemplateTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.172 s -- in cm.linked.fuseki.FusekiStoredTest
Tests run: 37, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.395 s -- in cm.linked.fuseki.DatasetGraphRDFSLiveTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.063 s -- in cm.linked.fuseki.TemplateGuardTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.001 s -- in cm.linked.fuseki.AssemblerTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.971 s -- in cm.linked.fuseki.fuseki.DropDatabaseFilterTest
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time:  19.839 s
```

`docker build -t linked-fuseki:071 server` (6.1 s, `sha256:b6ceac24dc9c…`); the module logs
`[linked-fuseki] module 6.2.0-2: lf:DatasetRDFSLive, /stored, SERVICE off, …`.

`SMOKE_PORT=3172 server/scripts/smoke.sh linked-fuseki:071` (4.6 s): 24 ok, 0 FAIL:

```
ok   server up
ok   POST $/datasets dbType=tdb2 (200)
ok   template dataset infers (/smoke/sparql) (true)
ok   named graph inferred (true)
ok   /smoke/stored holds no inferred triple (false)
ok   GET /smoke/stored quads = inserted (3)
ok   GET /smoke/data?default includes inferred triples (3)
ok   quads POST to the dataset URL (200)
ok   the POST changed the hierarchy (true)
ok   an unlisted graph does not feed the hierarchy (false)
ok   a graph the registry lists feeds the hierarchy (true)
ok   the unlisted graph still does not (false)
ok   the default graph still feeds (true)
ok   GSP PUT on the listed graph (200)
ok   the PUT replaced its hierarchy (true false)
ok   the rebuild logs the listed graph count
ok   SERVICE refused (422)
ok   ds infers (true)
ok   ds /stored stored-only (false)
ok   ds data read-write (ENABLE_DATA_WRITE) (200)
ok   ds text index (true)
ok   ds shacl: a shape on the superclass reports the subclass instance
ok   DELETE $/datasets/smoke (200)
ok   database directory removed
smoke: 5 s
```

`npx linked build && npm test` (6.1 s): `Test Suites: 3 passed, 3 total`, `Tests: 12 passed, 12 total`.

Quick gates after each phase: `mvn -B -f server test -Dtest=DatasetGraphRDFSLiveTest` 36 tests
(5.4 s); `-Dtest=AssemblerTest,FusekiStoredTest` 4 + 13 (14.0 s); `npm test` 12 (4.5 s).

The stale rule is tested by mutation: with `isHierarchyGraph` returning `false` for unlisted
graphs, `aConcurrentReaderDoesNotHideAWriteIntoANewlyListedGraph` and
`aWriteIntoANewlyListedGraphBeforeAnyRebuildIsSeen` fail (`expected: <true> but was: <false>`;
`expected: <n+1>`).

## Bench: 1M triples, hierarchy in 20 listed graphs

`LF_ONLY_BENCH=1 LF_PROJECT=fuseki-071-f1 LF_BENCH_PORT=3172 LF_IMAGE=linked-fuseki:071
LF_BENCH_DATA=server/bench/data server/probe/probe.sh` (3:47 total). Data from `gen.py` over
schema.org's 952 `subClassOf` (from `@_linked/schema`'s `schema.ttl`): 850,470 default-graph
triples, 99,862 in a named graph, as in report 001. `bench-listed` holds the same instances with
the hierarchy only in 20 listed graphs (`onto1`…`onto20`, records in the registry); its H1-H3
write the new axiom into `onto1`. The machine was shared with another step's tests (K1), so
medians are noisy (plain TDB2 Q1 6.3 ms as in report 001, but `bench` Q1 410 ms vs 234 ms then).

```
M10 bench: load 98354 ms; start 5080 ms; [linked-fuseki] /bench/db/listed/: hierarchy built (952 subClassOf, 20 graphs, 102 ms)
M10 Q1 count Thing	bench	cold	3180.7	med	410.0	p90	571.6	c=141546
M10 Q1 count Thing	bench-stock	cold	480.8	med	399.5	p90	541.9	c=141546
M10 Q1 count Thing	bench-plain	cold	16.3	med	6.3	p90	6.7	c=0
M10 Q2a count CreativeWork	bench	cold	143.0	med	123.8	p90	187.7	c=87502
M10 Q2a count CreativeWork	bench-stock	cold	103.8	med	128.0	p90	181.3	c=87502
M10 Q2a count CreativeWork	bench-plain	cold	4.5	med	4.8	p90	5.9	c=0
M10 Q2b count Photograph	bench	cold	6.1	med	6.7	p90	8.3	c=1662
M10 Q2b count Photograph	bench-stock	cold	8.8	med	12.3	p90	17.7	c=1662
M10 Q2b count Photograph	bench-plain	cold	30.3	med	12.2	p90	23.6	c=1662
M10 Q3a page CW off 0	bench	cold	1599.7	med	429.9	p90	547.0	rows=50
M10 Q3a page CW off 0	bench-stock	cold	429.4	med	448.2	p90	464.3	rows=50
M10 Q3a page CW off 0	bench-plain	cold	10.1	med	5.0	p90	5.9	rows=0
M10 Q3b page CW off 5000	bench	cold	910.3	med	841.6	p90	892.8	rows=50
M10 Q3b page CW off 5000	bench-stock	cold	799.3	med	877.8	p90	989.9	rows=50
M10 Q3b page CW off 5000	bench-plain	cold	6.9	med	8.4	p90	9.7	rows=0
M10 Q4 Event select	bench	cold	35.9	med	23.6	p90	34.2	rows=100
M10 Q4 Event select	bench-stock	cold	24.6	med	31.0	p90	36.2	rows=100
M10 Q4 Event select	bench-plain	cold	32.8	med	15.9	p90	20.1	rows=100
M10 Q5 ASK inst a Thing	bench	cold	15.5	med	16.8	p90	32.4	true
M10 Q5 ASK inst a Thing	bench-stock	cold	8.7	med	7.9	p90	12.8	true
M10 Q5 ASK inst a Thing	bench-plain	cold	5.4	med	8.9	p90	11.3	false
M10 Q6 inst ?p ?o	bench	cold	28.5	med	7.3	p90	13.7	rows=11
M10 Q6 inst ?p ?o	bench-stock	cold	6.2	med	7.4	p90	15.2	rows=11
M10 Q6 inst ?p ?o	bench-plain	cold	5.3	med	9.9	p90	18.0	rows=9
M10 Q7 owned walk	bench	cold	160.7	med	154.8	p90	175.0	rows=1000
M10 Q7 owned walk	bench-stock	cold	118.7	med	77.9	p90	84.5	rows=1000
M10 Q7 owned walk	bench-plain	cold	9.6	med	5.0	p90	9.0	rows=0
M10 Q9a GRAPH ?g Thing	bench	cold	171.0	med	27.2	p90	37.0	c=16629
M10 Q9a GRAPH ?g Thing	bench-stock	cold	22.2	med	25.1	p90	27.4	c=16629
M10 Q9a GRAPH ?g Thing	bench-plain	cold	7.7	med	4.0	p90	4.9	c=0
M10 Q8 INSERT DATA	bench	cold	32.0	med	10.0	p90	11.3
M10 Q8 DELETE DATA	bench	cold	23.1	med	10.4	p90	11.3
M10 Q8 INSERT DATA	bench-stock	cold	7.9	med	8.6	p90	9.3
M10 Q8 DELETE DATA	bench-stock	cold	8.8	med	8.0	p90	8.7
M10 Q8 INSERT DATA	bench-plain	cold	10.5	med	8.4	p90	9.5
M10 Q8 DELETE DATA	bench-plain	cold	7.8	med	9.5	p90	11.8
M10 H1 after subClassOf insert: ASK new instance a Thing	bench	first query ms	9.7	true
M10 H2 count Thing after the change	bench	ms	280.5	c=141547
M10 H3 after delete: ASK	bench	first query ms	8.3	false
M10L Q1 count Thing	bench-listed	cold	1020.1	med	256.5	p90	293.7	c=141546
M10L Q1 count Thing	bench	cold	270.1	med	231.8	p90	352.2	c=141546
M10L Q2a count CreativeWork	bench-listed	cold	236.0	med	132.5	p90	161.8	c=87502
M10L Q2a count CreativeWork	bench	cold	150.6	med	170.4	p90	182.1	c=87502
M10L Q2b count Photograph	bench-listed	cold	11.2	med	8.2	p90	10.0	c=1662
M10L Q2b count Photograph	bench	cold	10.7	med	6.8	p90	11.0	c=1662
M10L Q3a page CW off 0	bench-listed	cold	1358.3	med	504.5	p90	581.6	rows=50
M10L Q3a page CW off 0	bench	cold	490.5	med	513.2	p90	732.5	rows=50
M10L Q3b page CW off 5000	bench-listed	cold	1330.2	med	1507.4	p90	1722.0	rows=50
M10L Q3b page CW off 5000	bench	cold	2142.1	med	1658.7	p90	1979.0	rows=50
M10L Q4 Event select	bench-listed	cold	118.4	med	34.6	p90	47.6	rows=100
M10L Q4 Event select	bench	cold	77.2	med	24.7	p90	39.6	rows=100
M10L Q5 ASK inst a Thing	bench-listed	cold	9.0	med	6.7	p90	10.6	true
M10L Q5 ASK inst a Thing	bench	cold	10.3	med	8.4	p90	11.3	true
M10L Q6 inst ?p ?o	bench-listed	cold	14.7	med	11.9	p90	15.7	rows=11
M10L Q6 inst ?p ?o	bench	cold	12.1	med	20.9	p90	28.7	rows=11
M10L Q7 owned walk	bench-listed	cold	310.2	med	216.2	p90	256.1	rows=1000
M10L Q7 owned walk	bench	cold	387.5	med	359.1	p90	405.5	rows=1000
M10L Q9a GRAPH ?g Thing	bench-listed	cold	317.4	med	108.3	p90	285.8	c=16629
M10L Q9a GRAPH ?g Thing	bench	cold	94.5	med	52.6	p90	60.0	c=16629
M10L Q8 INSERT DATA	bench-listed	cold	74.6	med	36.9	p90	62.2
M10L Q8 DELETE DATA	bench-listed	cold	26.3	med	41.0	p90	48.5
M10L Q8 INSERT DATA	bench	cold	75.1	med	37.5	p90	52.5
M10L Q8 DELETE DATA	bench	cold	56.4	med	33.5	p90	55.4
M10L H1 after subClassOf insert: ASK new instance a Thing	bench-listed	first query ms	84.0	true
M10L H2 count Thing after the change	bench-listed	ms	1018.4	c=141547
M10L H3 after delete: ASK	bench-listed	first query ms	16.1	false
M10 rebuild log: [linked-fuseki] /bench/db/main/: hierarchy rebuilt (953 subClassOf, 0 graphs, 5 ms, generation 1) [linked-fuseki] /bench/db/main/: hierarchy rebuilt (952 subClassOf, 0 graphs, 3 ms, generation 2) [linked-fuseki] /bench/db/listed/: hierarchy rebuilt (953 subClassOf, 20 graphs, 43 ms, generation 1) [linked-fuseki] /bench/db/listed/: hierarchy rebuilt (952 subClassOf, 20 graphs, 6 ms, generation 2) 
```

- **Rebuild with 20 listed graphs: 43 ms** (first, after the H1 insert into `onto1`) **and 6 ms**
  (after the delete), vs 5 and 3 ms with the hierarchy in the default graph; **build at open
  102 ms** vs 24 ms. The first read after the change (H1) took 84 ms vs 9.7 ms.
- Query medians on `bench-listed` match `bench` within the run's noise (`M10L` lines, both in the
  same pass): Q1 256 vs 232 ms, Q3a 505 vs 513 ms, Q7 216 vs 359 ms; Q9a (`GRAPH ?g`) 108 vs 53 ms
  (bench-listed has 21 more named graphs to iterate).
- Every count is identical (Q1 `c=141546`, H2 `c=141547`): the hierarchy from listed graphs infers
  exactly what the default-graph hierarchy does.

## Deviations from the sub-plan

- **Every graph handle is wrapped** (the contract asks for the registry and listed graphs): whether
  a graph feeds is decided per write, so a graph listed by a record in the same transaction (or
  since) is tracked. Q9a shows no cost from it on `bench`.
- **The stale rule** (sub-plan §Design) is in addition to the contract's marking: while a registry
  commit is newer than the published set, every named graph's `subClassOf` writes mark. It only
  adds marks.
- **Records naming `urn:x-arq:DefaultGraph` or `urn:x-arq:UnionGraph` list nothing** (review L1:
  the union name would have pulled every graph, drafts included, into the hierarchy without
  tracking them).

## Follow-ups

- F2 (README graph-name mapping, the minor changeset) — plan 071 §F2, sub-plan 002 §F2.
- Image publish (`ghcr.io/linked-fw/fuseki:6.2.0-2`) at R1 (plan 071 §R1); until then later steps
  use the local `linked-fuseki:071`.

## Independent review

An independent read-only reviewer (opus) reviewed `aa73d9b..a7a3d6d` against plan 071 §F1,
§Contracts (§The Fuseki module, §Graph IRIs and vocabulary) and sub-plan 002. It ran the three
JUnit classes in a scratch copy and confirmed the concurrent stale test by mutation. Verdict,
quoted: **"Verdict: No open medium or high findings."**

- L1 (low): a record naming the union graph made every named graph feed without being tracked.
  Fixed (73a1404): such names are skipped; test `recordsNamingTheDefaultOrUnionGraphListNothing`.
- L2 (low): the sequential stale test passed without the stale rule. Fixed: it asserts the
  write-only commit moves the generation; it now fails under the mutation.
- N1 (nit): the logged build time could belong to another build. Fixed: the time travels in `View`.
- N2 (nit): an overlong Javadoc line. Fixed.
