---
summary: >
  Fuseki's sub-plan for CN plan 070 (the store infers): step F1. A Java module in `server/`
  (dataset type `lf:DatasetRDFSLive`: Jena's query-time RDFS view over TDB2 whose class hierarchy
  is rebuilt lazily after commits that touch the stored `rdfs:subClassOf` triples; a stored-only
  `/<ds>/stored` endpoint; SERVICE off), the image `ghcr.io/linked-fw/fuseki:6.2.0-1` with the
  inferring `config-tdb2` template and an inferring `ds` dataset, its CI and GHCR publishing, the
  measurements that settle the *pending F1* contract items, the `storedEndpoint` helper and
  the README.
status: Plan
packages: [fuseki]
---

# 001 — Store inference: the RDFS module, the image and the stored endpoints

Sub-plan of CN plan 070,
[`docs/plans/070-store-inference-and-ontology-data.md`](https://github.com/Semantu/create-now/blob/store-inference/docs/plans/070-store-inference-and-ontology-data.md)
(locally `~/web/wt/070/docs/plans/070-store-inference-and-ontology-data.md`). Read its §Decisions
(2-9 are this step's design), §How to use, §Rules and §Contracts first. F1 is a **sensitive** step:
an independent read-only review of the diff runs before it lands. Record: report 001
(`docs/reports/001-store-inference.md`, new).

## Contracts

This repository **provides** both contracts below; it consumes none. Copied verbatim from the
master plan's §Contracts (the rest of the contracts:
[master plan §Contracts](https://github.com/Semantu/create-now/blob/store-inference/docs/plans/070-store-inference-and-ontology-data.md#contracts)).

<!-- verbatim:start -->

Each item names its provider and consumers. Items marked *pending F1* are settled by F1's
measurements; the master edits them here before C1 and K1's store phase start.

### The image (provider F1; consumers C1, W1, W2, T1, K1's test store, L1's e2e)

- `ghcr.io/linked-fw/fuseki:<jena>-<n>` (first: `6.2.0-1`), `FROM secoresearch/fuseki:6.2.0`;
  until the release, built locally from `linked-fw/fuseki`'s `server/Dockerfile` (compose
  `build:` pointing at `~/web/wt/070/packages-local/_linked-fuseki/server`, tag
  `linked-fuseki:dev`). Same env as the base image (`ADMIN_PASSWORD`, `ENABLE_DATA_WRITE`,
  `ENABLE_UPDATE`, `ENABLE_SHACL`, `QUERY_TIMEOUT`), `FUSEKI_BASE=/fuseki-base`, uid 9008.
- Dataset type `lf:DatasetRDFSLive` (`PREFIX lf: <https://linked.cm/ont/fuseki#>`), declared in an
  assembler as:
  ```turtle
  :ds a lf:DatasetRDFSLive ; ja:dataset :tdb .
  :tdb a tdb2:DatasetTDB2 ; tdb2:location "/fuseki-base/databases/<name>" .
  ```
  It infers over every graph; its hierarchy is the closure of the stored `rdfs:subClassOf`
  triples in the default graph. A commit that adds or removes one marks the hierarchy stale and
  bumps a generation counter; the first transaction to begin afterwards rebuilds it once (other
  starting readers wait), and each transaction keeps the hierarchy it began with. A burst of sync
  commits costs one rebuild; a writer sees its own `subClassOf` change from its next transaction.
- The image keeps the base image's `ds` dataset and its `shacl` endpoint under
  `ENABLE_SHACL=true`, as `lf:DatasetRDFSLive` over TDB2 at `/fuseki-base/databases/ds` (the base
  image's `ds` is TDB1 with a union default graph; its Lucene index and `ENABLE_*` handling are
  kept). Core's SHACL tests validate through it.
- The module rewrites `$FUSEKI_BASE/templates/config-tdb2` at start when it is missing or
  different, with a warning (Fuseki copies its stock template only when the file is missing, so a
  persisted volume would otherwise create plain datasets silently).
- `templates/config-tdb2` (baked in) declares the service with the endpoints CN uses today
  (`""`/`sparql`/`query`, `""`/`update`, `""`/`data` GSP read-write, `upload`, `get`) over
  `lf:DatasetRDFSLive`, plus `stored` and `shacl` (store validation, decision 23). A form-created dataset (`POST $/datasets`,
  `dbName`, `dbType=tdb2`) gets it. **Only `dbType=tdb2` datasets infer**; `mem` (`config-mem`)
  stays plain.
- `/<ds>/stored`: one module operation (`lf:stored`) over the stored triples only (every graph),
  run by TDB2's own query engine: a SPARQL query or a GSP read, chosen from the request; writes
  answer 405.
- **Backups and copies stream `GET /<ds>/stored` as N-Quads.** `POST $/backup` and `GET /<ds>`
  dump the service's dataset, inferred triples included, so they are never used for these
  datasets.
- `DELETE $/datasets/<ds>` removes the TDB2 files too (a module filter finishes what Fuseki skips
  for a wrapped dataset); a re-created name starts empty.
- `arq:httpServiceAllowed=false` set by the module for every dataset.
- *pending F1:* whether `text:TextDataset` (pg-test) works over `lf:DatasetRDFSLive`.

### `@_linked/fuseki` (provider F1; consumers C1, W1, W2, T1)

```ts
// @_linked/fuseki/utils/datasets — existing signatures unchanged (dbType stays: the template infers)
export function storedEndpoint(base: string, dataset: string): string;   // `${base}/${dataset}/stored`: query and GSP read
```
README: the image, the template, the stored endpoints, the graph rule (decision 6).
<!-- verbatim:end -->

### Contract changes this sub-plan proposes

Raised with the master when this sub-plan was written. Items 1-3 and 5-7 are **adopted in the
master plan** (its §Contracts, copied above); item 4 stays *pending F1*. The reasoning is kept.

1. **One stored endpoint name** *Adopted in the master plan, as one `storedEndpoint`.* (settles the first *pending F1*, from the Jena source; F1's
   measurements confirm it). Fuseki's `Dispatcher.chooseOperation` only ever returns the standard
   operations (`Query`, `Update`, `GSP_R`, `GSP_RW`, or one registered by content type), so two
   custom operations under one endpoint name are never told apart. The module registers **one**
   operation, `lf:stored`, whose handler picks SPARQL query (a `query` parameter, or
   `Content-Type: application/sparql-query`) or GSP read (anything else: no query string for the
   whole dataset as quads, `?default`, `?graph=<iri>`). An endpoint name with exactly one operation
   is dispatched directly (`EndpointSet.getExactlyOne()`). Proposed text:
   > `/<ds>/stored`: one endpoint, SPARQL query and GSP read over the stored triples only (every
   > graph). `GET /<ds>/stored` with `Accept: application/n-quads` is the whole stored dataset;
   > `?default` / `?graph=<iri>` one graph; `?query=` or a `application/sparql-query` POST a query.
   > Writes answer 405. For a dataset that is not `lf:DatasetRDFSLive`, `stored` serves the
   > dataset as it is.
   ```ts
   export function storedQueryEndpoint(base: string, dataset: string): string;   // `${base}/${dataset}/stored`
   export function storedDataEndpoint(base: string, dataset: string): string;    // the same URL: the endpoint picks query or GSP
   ```
2. **Backups** *Adopted in the master plan.* (second *pending F1*). `ActionBackup` dumps `ActionCtl.getItemDataset(action)`, the
   service's dataset, with `RDFDataMgr.write(out, dsg, Lang.NQUADS)` (`Backup.java:115`); over the
   view that is stored **plus inferred** triples, and a restore would materialize them. Expected
   text (F1's M3 confirms):
   > `POST $/backup/<ds>` dumps stored and inferred triples: never use it for an
   > `lf:DatasetRDFSLive` dataset. Backups stream `GET /<ds>/stored` (`Accept: application/n-quads`).
   > `GET /<ds>` (the dataset URL) includes inferred quads too: copies read `/stored`.
3. **Delete** *Adopted in the master plan.* (third *pending F1*). `ActionDatasets` deletes the database files only when
   `TDBInternal.isTDB2(dataService.getDataset())`, which is `instanceof DatasetGraphSwitchable`
   (`ActionDatasets.java:467-476`, `TDBInternal.java:53-55`): false for the wrapper. Fuseki still
   expels the TDB2 database (`DataService.expel` unwraps through `getWrapped()`), so the files are
   closed but left on disk, and a later dataset of the same name would reopen the old data. The
   module adds a servlet filter that completes the delete. Expected text (F1's M4 confirms):
   > `DELETE $/datasets/<ds>` removes the dataset's TDB2 files under `/fuseki-base/databases/` (the
   > module completes what Fuseki skips for wrapped datasets); a re-created name starts empty.
4. **Text index** *Still pending F1 in the master plan.* (fourth *pending F1*): expected to work (`text:TextDataset` wraps any assembled
   dataset; `DatasetGraphText` is a `DatasetGraphWrapper`, and the module unwraps through it).
   Proposed text after M5: "`text:TextDataset` works over `lf:DatasetRDFSLive` (`text:dataset` →
   the live type → TDB2), as pg-test declares it."
5. **Lazy rebuild** *Adopted in the master plan.* (coordinator's addition, from core's K1 detail: a first ontology sync is ~800
   small commits that each touch `rdfs:subClassOf`). Replace "rebuilt after a commit that adds or
   removes one (and at start)" with:
   > its hierarchy is the closure of the stored `rdfs:subClassOf` triples in the default graph. A
   > commit that wrote or deleted such a triple only marks the hierarchy stale; the first
   > transaction that begins after it rebuilds it once (concurrent beginners wait for that one
   > rebuild), so a burst of commits costs one rebuild. A transaction sees the hierarchy as of its
   > begin; a writer sees its own `subClassOf` changes from its next transaction on.
6. **The image's `ds` dataset** *Adopted in the master plan.* (coordinator's addition; core's SHACL tests validate through it).
   Add to §The image:
   > The image keeps the base image's `ds` service (`/fuseki-base/configuration/assembler.ttl`,
   > `sparql` and `data` read; `update`, `data` read-write and `shacl` switched on by
   > `ENABLE_UPDATE`, `ENABLE_DATA_WRITE`, `ENABLE_SHACL=true` as in the base image), its dataset
   > `lf:DatasetRDFSLive` over TDB2 at `/fuseki-base/databases/ds` (the base image's is TDB1 with
   > `tdb:unionDefaultGraph true`), with the base image's Lucene text index. `ENABLE_*` act on
   > `ds` only; template-created datasets always have `update` and `data` read-write.
   The base image's `ds` is `tdb:DatasetTDB` (TDB1) at `/fuseki-base/databases/tdb` with
   `tdb:unionDefaultGraph true`; decision 6 keeps the union default graph off and decision 2 wraps
   TDB2, so the image's `ds` changes both. Nothing persists in `ds` across consumers today (core's
   tests are throwaway); the README says so.
7. **SHACL on template datasets** *Adopted in the master plan.* (a gap, not a decision): decision 23 and §Features say the
   store's SHACL validation runs over the view, but the stock `config-tdb2` (and so the contract's
   template) has no `shacl` endpoint. Proposed: the template adds
   `fuseki:endpoint [ fuseki:operation fuseki:shacl ; fuseki:name "shacl" ]`. F1 adds it only if
   the master puts it in §Contracts.

## F1 — the module, the image and the template

**Depends on:** nothing (wave 1). K1's store phase and L1's e2e phase wait for F1's landing.

**Goal:** one image every consumer runs, whose datasets infer classes and expose stored-only reads.

**Scope:**
1. `server/` (excluded from the npm package): Maven project, Java 21, `jena-fuseki-main` 6.2.0
   `provided` (it brings `jena-arq` and `jena-tdb2`). The dataset type `lf:DatasetRDFSLive`
   registered through `JenaSubsystemLifecycle` (ServiceLoader); a `DatasetGraphRDFS` view over
   TDB2 whose setup is rebuilt lazily after commits that touched an `rdfs:subClassOf` triple of the
   default graph; the stored-only operation `lf:stored`; a `FusekiAutoModule` that sets
   `arq:httpServiceAllowed=false`, registers the operation's handler, keeps the template in place
   and completes `DELETE $/datasets`.
2. `server/Dockerfile` `FROM secoresearch/fuseki:6.2.0`: the jar in `/javalibs/`, the inferring
   `templates/config-tdb2`, the inferring `ds` assembler. GitHub Actions: build and test on every
   push; publish `ghcr.io/linked-fw/fuseki:<tag>` (public) from `main` when `server/IMAGE_TAG`
   changes.
3. Measurements that settle every *pending F1* item and the two additions (Phase 2, M1-M13).
4. `@_linked/fuseki`: `storedEndpoint`; README; a minor changeset.

**Verify** (each quoted with its output and timing in report 001):
```bash
cd ~/web/wt/070-F1/packages-local/_linked-fuseki        # the step's worktree
mvn -B -f server verify                                  # JUnit, list under Tests
docker build -t linked-fuseki:dev server                 # the image
server/scripts/smoke.sh linked-fuseki:dev                # template dataset infers; /stored; SERVICE refused; ds + shacl
server/probe/probe.sh                                    # M1-M13 (Phase 2; prints one result line per item)
npx linked build && npm test                             # the TS package
npm pack --dry-run 2>&1 | grep -c 'server/'              # 0: server/ is not in the tarball
```

**Done when:** every Verify command passes; report 001 quotes M1-M13 with the final contract text
for each *pending F1* item and the master has it; the image builds from `server/` alone (compose
`build:` with context `.../_linked-fuseki/server`, tag `linked-fuseki:dev`); the two workflows run
green on the pushed `store-inference` branch (the image workflow's publish job only on `main`, so
it is first exercised at R); the README sections below exist; a changeset (minor) is added; the
independent review has no open medium or high finding.

### Design

Jena 6.2.0 classes this relies on (checked against tag `jena-6.2.0`, and with `javap` against the
`fuseki-server.jar` of `secoresearch/fuseki:6.2.0`, `Implementation-Version: 6.2.0`):

| Class | What F1 uses | Source (jena-6.2.0) |
|---|---|---|
| `org.apache.jena.rdfs.DatasetGraphRDFS` | `new DatasetGraphRDFS(DatasetGraph, SetupRDFS, Context)`; extends `DatasetGraphWithGraphTransform`, implements `DatasetGraphWrapperView` ("Do not unwrap for query execution"); its `SetupRDFS` is a `final` field, so a new hierarchy means a new view object | `jena-arq/.../rdfs/DatasetGraphRDFS.java` |
| `org.apache.jena.rdfs.SetupRDFS` | `new SetupRDFS(Graph vocab)`; `BaseSetupRDFS` computes the transitive `subClassOf` (and `subPropertyOf`, `domain`, `range`) maps once, in its constructor | `rdfs/SetupRDFS.java`, `rdfs/setup/BaseSetupRDFS.java:79-121` |
| `org.apache.jena.rdfs.engine.DatasetGraphWithGraphTransform` | `getDefaultGraph/getGraph/getUnionGraph` wrap `getG()`'s graphs in `GraphRDFS`; `find` infers per graph (default, each named, union); writes are not overridden, so they go to `DatasetGraphWrapper.getW()` | `rdfs/engine/DatasetGraphWithGraphTransform.java` |
| `org.apache.jena.rdfs.GraphRDFS` → `GraphMatch` → `org.apache.jena.sparql.graph.GraphWrapper` | graph writes pass to the wrapped graph | `rdfs/GraphRDFS.java`, `rdfs/engine/GraphMatch.java:37` |
| `org.apache.jena.rdfs.RDFSFactory` | `setupRDFS(Graph)`, `datasetRDFS(DatasetGraph, SetupRDFS)` (what stock `ja:DatasetRDFS` uses) | `rdfs/RDFSFactory.java` |
| `org.apache.jena.rdfs.assembler.VocabRDFS` / `DatasetRDFSAssembler` | the pattern copied: `AssemblerUtils.registerDataset(type, new …Assembler())`; base dataset through `DatasetAssembler.createBaseDataset(root, ja:dataset)`; `AssemblerUtils.mergeContext(root, dsg.getContext())` | `rdfs/assembler/*.java`, `sparql/core/assembler/DatasetAssembler.java` |
| `org.apache.jena.sparql.core.assembler.AssemblerUtils` | `static void registerDataset(Resource, Assembler)` | `sparql/core/assembler/AssemblerUtils.java` |
| `org.apache.jena.sys.JenaSubsystemLifecycle` | `start()`, `stop()`, `level()`; `InitRDFS` is level 60 | `rdfs/sys/InitRDFS.java` |
| `org.apache.jena.sparql.core.DatasetGraphWrapper` | `protected DatasetGraph get()` ("all calls go via get()"); `getR/getW/getG/getT` default to `get()`; `public final getWrapped()` returns `get()` | `sparql/core/DatasetGraphWrapper.java:40-117` |
| `org.apache.jena.fuseki.main.sys.FusekiAutoModule` | `name()`, `prepare(FusekiServer.Builder, Set<String>, Model)`; loaded by ServiceLoader (`META-INF/services/org.apache.jena.fuseki.main.sys.FusekiAutoModule`), sorted by `level()` | `fuseki-main/.../sys/FusekiAutoModule.java`, `FusekiAutoModules.java:93-133` |
| `FusekiServer.Builder` | `registerOperation(Operation, ActionService)`, `addFilter(String, Filter)`; `build()` calls modules' `prepare` and **then** freezes the registry (`new OperationRegistry(operationRegistry)`), so registration must happen in `prepare` | `FusekiServer.java:1312-1330, 1278, 1465-1474` |
| `org.apache.jena.fuseki.server.Operation` | `Operation.alloc(String iri, String name, String description)` (idempotent, `computeIfAbsent`); config parsing resolves `fuseki:operation` with `Operation.get(node)` and fails ("No implementation for fuseki:operation") when it is not yet allocated | `Operation.java:12-40`, `FusekiConfig.java:546-570` |
| `org.apache.jena.fuseki.servlets.SPARQL_QueryDataset` | `protected DatasetGraph getDataset(HttpAction)` (default `action.getActiveDSG()`) | `SPARQL_QueryDataset.java:66` |
| `org.apache.jena.fuseki.servlets.GSP_R` | `protected DatasetGraph decideDataset(HttpAction)` (from `ActionREST:59`); GET with no query string writes the whole dataset as quads | `GSP_R.java:43-80`, `GSP_Base.java:42` |
| `org.apache.jena.fuseki.servlets.ActionService` / `ActionProcessor` | `process(HttpAction)` dispatches by method; `validate`/`execute` | `ActionService.java`, `ActionProcessor.java:34-66` |
| `org.apache.jena.tdb2.sys.TDBInternal` | `isTDB2(dsg)` = `instanceof DatasetGraphSwitchable`; `isBackedByTDB` likewise, which is why the view runs on ARQ's generic engine and the raw TDB2 dataset on TDB2's | `TDBInternal.java:53-55, 265-271`, `tdb2/solver/QueryEngineTDB.java:121-146` |
| `org.apache.jena.query.ARQ.httpServiceAllowed` | the `Symbol` set to `false` in the global context | `ARQ.java:291` |

**Namespace and names:** `PREFIX lf: <https://linked.cm/ont/fuseki#>`; Java package
`cm.linked.fuseki`; Maven `cm.linked:linked-fuseki-module`, jar
`linked-fuseki-module.jar`; operation `lf:stored` (display name `stored`).

**Package layout** (`server/src/main/java/cm/linked/fuseki/`):

| Class | Purpose |
|---|---|
| `LF` | Vocabulary: `NS`, `DatasetRDFSLive` (Resource), `stored` (Operation, allocated with `Operation.alloc(NS+"stored", "stored", "Stored triples only (no inference)")`), `init()` idempotent: `AssemblerUtils.registerDataset(DatasetRDFSLive, new DatasetRDFSLiveAssembler())` and touches the Operation. |
| `sys/InitLinkedFuseki` | `JenaSubsystemLifecycle`, `level()` 500 (after ARQ, TDB2 and `InitRDFS`'s 60), `start()` → `LF.init()`. Registered in `META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle`. This is what makes `lf:DatasetRDFSLive` and `lf:stored` known **before** any configuration file or template is parsed. |
| `rdfs/DatasetRDFSLiveAssembler` | `extends DatasetAssembler`; `createDataset(a, root)`: `base = createBaseDataset(root, ja:dataset)` (error when missing), `new DatasetGraphRDFSLive(base, label)` (label: the TDB2 location, else the root's IRI), `AssemblerUtils.mergeContext(root, dsg.getContext())`. Not a `NamedDatasetAssembler`: no shared pool. |
| `rdfs/SubClassOfTracker` | `extends DatasetGraphWrapper` over the stored dataset. Overrides `add(Quad)`, `add(g,s,p,o)`, `delete(Quad)`, `delete(g,s,p,o)`, `deleteAny`, `clear()`, `addGraph`, `removeGraph` to mark the hierarchy stale when the graph is the default graph (`Quad.isDefaultGraph`) or a wildcard and the predicate is `rdfs:subClassOf` or a wildcard; `getDefaultGraph()`, `getGraph(n)`, `getUnionGraph()` return `TrackingGraph`s, because a graph obtained from TDB2 (`GraphViewSwitchable`) writes to TDB2 directly and would bypass the dataset-level overrides (GSP PUT, `CLEAR`, Upload through a graph). A stale mark is per write transaction: `AtomicBoolean touched`, read and reset by the live dataset. |
| `rdfs/TrackingGraph` | `extends GraphWrapper`; `add(Triple)`, `delete(Triple)`, `remove(s,p,o)`, `clear()` mark the tracker when it wraps the default graph (or the union graph, conservatively) and the predicate is `subClassOf` or a wildcard. (`Graph.add(s,p,o)`/`delete(s,p,o)` are default methods calling `add/delete(Triple)`, `Graph.java:90,110`.) |
| `rdfs/Hierarchy` | `static Graph read(DatasetGraph stored)`: the `find(Quad.defaultGraphIRI, ANY, rdfs:subClassOf, ANY)` triples whose object is an IRI or blank node, into a `GraphMemFactory.createDefaultGraph()`; only `subClassOf`, so `SetupRDFS` ignores `subPropertyOf`, `domain`, `range` (decision 3). `static SetupRDFS setup(Graph)`. |
| `rdfs/DatasetGraphRDFSLive` | `extends DatasetGraphWrapper implements DatasetGraphWrapperView`. Holds `stored` (the base, normally TDB2's `DatasetGraphSwitchable`), `tracker` (over `stored`), `volatile View current` (a `DatasetGraphRDFS` over `tracker` plus the generation it was built for), `AtomicLong generation`, a rebuild lock, `ThreadLocal<View> pinned`. `get()` returns the pinned view, else `current` (so reads infer and writes go view → tracker → stored). **Begin** (every `begin` overload, and `promote` keeps the pin): `super.begin(..)`; then if `current.generation < generation.get()`, take the lock, re-check, rebuild from `Hierarchy.read(stored)` **inside this transaction** (it sees every commit that finished before it began), set `current`; pin `current`. **Commit:** `boolean touched = tracker.takeTouched(); super.commit(); if (touched) generation.incrementAndGet();` (no read, no rebuild). **Abort/end:** reset the tracker's mark, remove the pin. Construction builds generation 0 in a read transaction (`Txn.calculateRead(stored, ..)`). Counters `rebuilds()`, `lastRebuildMillis()`, `generation()` for the tests and the log line `[linked-fuseki] <label>: hierarchy rebuilt (<n> subClassOf, <ms> ms, generation <g>)` at INFO. `stored()` returns `stored`. |
| `rdfs/LiveDatasets` | `static Optional<DatasetGraphRDFSLive> find(DatasetGraph)`: walks `DatasetGraphWrapper.getWrapped()` (through `DatasetGraphText`, `DatasetGraphTextMonitor extends DatasetGraphWrapper`); `static DatasetGraph stored(DatasetGraph)`: the live dataset's `stored()`, or the argument itself. |
| `fuseki/StoredQuery` | `extends SPARQL_QueryDataset`; `getDataset(action)` → `LiveDatasets.stored(action.getActiveDSG())` (the raw TDB2 dataset: TDB2's own query engine, the same transaction the action began on the service dataset). |
| `fuseki/StoredGSP` | `extends GSP_R`; `decideDataset(action)` → `LiveDatasets.stored(action.getActiveDSG())`. Writes answer 405 (GSP_R's `doPost/doPut/doDelete/doPatch`). |
| `fuseki/StoredReadService` | `extends ActionService`; `process(action)` (and `validate`/`execute`) delegate to `StoredQuery` when the request has a `query` parameter or `Content-Type: application/sparql-query` (or the HTTP `QUERY` method), else to `StoredGSP`. |
| `fuseki/DropDatabaseFilter` | `jakarta.servlet.Filter` on `/$/datasets/*`: for `DELETE`, before the chain it resolves the dataset (`DataAccessPointRegistry.get(servletContext)`), and when `LiveDatasets.find` is present and `stored` is TDB2 whose location is a direct child of `$FUSEKI_BASE/databases/` and not a symbolic link, remembers the path; after the chain, on a 2xx status, deletes it (`IO.deleteAll`) and logs it. The same rule as Fuseki's own (`ActionDatasets.java:476-492`). Kept only if M4 shows the files stay (the source says they do). |
| `fuseki/TemplateGuard` | When `FUSEKI_BASE` is set (`Lib.getenv`, system property first), makes `$FUSEKI_BASE/templates/config-tdb2` equal the bundled template (a classpath resource copied from `server/templates/config-tdb2` by the pom): writes it when missing or different, logging a WARN when it replaced a file. Reason: Fuseki copies its stock templates only when missing (`FusekiServerCtl.java:203-205`), so a consumer with a persisted `/fuseki-base` volume from before the image would create non-inferring datasets silently. |
| `fuseki/FMod_LinkedFuseki` | `FusekiAutoModule`, `name()` `"LinkedFuseki"`. `prepare(builder, names, model)`: `ARQ.getContext().set(ARQ.httpServiceAllowed, false)` (the probe `FMod_Probe` showed this works from `/javalibs`); `LF.init()`; `builder.registerOperation(LF.stored, new StoredReadService())`; `TemplateGuard.ensure()`; `builder.addFilter("/$/datasets/*", new DropDatabaseFilter())`; one INFO line naming the module, its version and the settings. Registered in `META-INF/services/org.apache.jena.fuseki.main.sys.FusekiAutoModule`. |

Write paths the tracker must see (each has a test): SPARQL `INSERT DATA`, `DELETE DATA`,
`DELETE WHERE`, `INSERT {..} WHERE`, `CLEAR DEFAULT`, `CLEAR ALL`, `DROP ALL`, `LOAD`; GSP `PUT`,
`POST`, `DELETE` on `?default`; `upload`; quads POST to the dataset URL. A `subClassOf` triple in a
named graph does not mark it; neither does any other predicate. A delete of an inferred triple is
a no-op (it reaches TDB2, which holds nothing to delete).

**`server/templates/config-tdb2`** (the stock Jena 6.2.0 template with the dataset swapped and
`stored` added; keep the stock endpoints exactly):
```turtle
# The template Fuseki uses for `POST $/datasets` with dbType=tdb2 (ActionDatasets → Template.templateTDB2_FN).
# Linked: the dataset infers classes (lf:DatasetRDFSLive) and /stored reads the stored triples only.

PREFIX :        <#>
PREFIX fuseki:  <http://jena.apache.org/fuseki#>
PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs:    <http://www.w3.org/2000/01/rdf-schema#>
PREFIX tdb2:    <http://jena.apache.org/2016/tdb#>
PREFIX ja:      <http://jena.hpl.hp.com/2005/11/Assembler#>
PREFIX lf:      <https://linked.cm/ont/fuseki#>

<#service_tdb_all> rdf:type fuseki:Service ;
    rdfs:label                         "TDB2 {NAME}, RDFS classes" ;
    fuseki:name                        "{NAME}" ;
    fuseki:serviceQuery                "" ;
    fuseki:serviceQuery                "sparql" ;
    fuseki:serviceQuery                "query" ;
    fuseki:serviceUpdate               "" ;
    fuseki:serviceUpdate               "update" ;
    fuseki:serviceUpload               "upload" ;
    fuseki:serviceReadWriteGraphStore  "data" ;
    fuseki:serviceReadGraphStore       "get" ;
    fuseki:endpoint [ fuseki:operation lf:stored ; fuseki:name "stored" ] ;
    fuseki:dataset                     <#dataset> ;
    .

<#dataset> rdf:type lf:DatasetRDFSLive ;
    ja:dataset <#tdb_dataset_readwrite> ;
    .

# No tdb2:unionDefaultGraph: the default graph is the stored default graph (CN plan 070, decision 6).
<#tdb_dataset_readwrite> rdf:type tdb2:DatasetTDB2 ;
    tdb2:location "{FUSEKI_BASE}/databases/{NAME}" ;
    .
```

**`server/configuration/assembler.ttl`** (the `ds` service, replacing the base image's): the base
file's prefixes and its `<#service>` block **line for line** (the entrypoint's `ENABLE_*` `sed`
expressions match those exact commented lines: `#fuseki:serviceUpdate`,
`#fuseki:serviceReadWriteGraphStore`, `fuseki:serviceReadGraphStore`, and the one-line
`#fuseki:endpoint [ fuseki:operation fuseki:shacl ; fuseki:name "shacl" ] ;`), plus
`fuseki:endpoint [ fuseki:operation lf:stored ; fuseki:name "stored" ]`; then
`<#text> text:TextDataset ; text:dataset <#live>`, `<#live> a lf:DatasetRDFSLive ; ja:dataset <#tdb>`,
`<#tdb> a tdb2:DatasetTDB2 ; tdb2:location "/fuseki-base/databases/ds"` (no union default graph),
and the base file's `<#lucene>` and `<#entity-map>` unchanged.

**`server/Dockerfile`:**
```dockerfile
# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
COPY templates ./templates
# Tests run in CI (mvn verify) before any image is built or published.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM secoresearch/fuseki:6.2.0@sha256:74833bbf3967f41f5f98ddd6b861abf1a426705056da3bbdb516b8a703342aa9
USER root
COPY --from=build /src/target/linked-fuseki-module.jar /javalibs/linked-fuseki-module.jar
COPY templates/config-tdb2 /fuseki-base/templates/config-tdb2
COPY configuration/assembler.ttl /fuseki-base/configuration/assembler.ttl
# The image runs as uid 9008, group 0; the entrypoint edits assembler.ttl in place (ENABLE_*).
RUN chown -R 9008:0 /fuseki-base/templates /fuseki-base/configuration \
 && chmod -R g+rwX /fuseki-base/templates /fuseki-base/configuration
USER 9008
LABEL org.opencontainers.image.source="https://github.com/linked-fw/fuseki" \
      org.opencontainers.image.description="Apache Jena Fuseki 6.2.0 whose datasets infer RDFS classes, with stored-only reads" \
      org.opencontainers.image.licenses="Apache-2.0"
```
Entrypoint and `CMD` stay the base image's (`java -cp '*:/javalibs/*' …FusekiServerCmd`, working
directory `/jena-fuseki`). The base image has no `/fuseki-base/templates` until its first start
(Fuseki copies the stock templates when missing), so the baked file wins. `server/.dockerignore`:
`target/`, `probe/`, `bench/`.

**`server/pom.xml` essentials:** `groupId` `cm.linked`, `artifactId` `linked-fuseki-module`,
`packaging` jar, `maven.compiler.release` 21 (the local Maven runs on JDK 25; the image runs
Temurin 21.0.11), `project.build.sourceEncoding` UTF-8, property `jena.version` 6.2.0;
dependencies `org.apache.jena:jena-fuseki-main:${jena.version}` **provided**,
`org.apache.jena:jena-text:${jena.version}` **test**, `org.junit.jupiter:junit-jupiter` (through
`junit-bom`, current release) **test**, `org.slf4j:slf4j-simple` **test**; build: `finalName`
`linked-fuseki-module`, `templates/` as a resource directory with `targetPath`
`cm/linked/fuseki` (the bundled template for `TemplateGuard`), `maven-surefire-plugin` 3.x with
`reuseForks=false` for the tests that start Fuseki with a run area (Fuseki's run-area paths are
static), `maven-jar-plugin` writing `Implementation-Version` (the image tag) into the manifest. No
shading: the jar contains only the module's classes and service files.

**GitHub Actions** (the npm release through changesets, `publish.yml` and `pr.yml`, is untouched;
the image has its own version, `server/IMAGE_TAG`, and its own workflow):
- `.github/workflows/server.yml` "Server": `on: push` (all branches) and `pull_request` (main),
  `paths: [server/**, .github/workflows/server.yml, .github/workflows/image.yml]`; job
  `module`: `actions/setup-java` (temurin 21, `cache: maven`), `mvn -B -f server verify`,
  `docker build -t linked-fuseki:ci server`, `server/scripts/smoke.sh linked-fuseki:ci`. Not a
  required check (`main`'s required check is `checks / Build & Test`; a path-filtered required
  check would block PRs that do not touch `server/`).
- `.github/workflows/image.yml` "Image": `on: push` to `main` with `paths: [server/**]`, and
  `workflow_dispatch`; `permissions: contents: read, packages: write`; steps: read
  `TAG=$(cat server/IMAGE_TAG)` (`<jena>-<n>`, first `6.2.0-1`); if
  `docker manifest inspect ghcr.io/linked-fw/fuseki:$TAG` succeeds, stop with a notice (tags are
  immutable: bump `IMAGE_TAG` to publish); `mvn -B -f server verify`; build `linux/amd64` with
  `--load` and run `smoke.sh`; `docker/setup-qemu-action`, `docker/setup-buildx-action`,
  `docker/login-action` (ghcr.io, `github.actor`, `GITHUB_TOKEN`), `docker/build-push-action`
  with `platforms: linux/amd64,linux/arm64` (the base is multi-arch; the owner's Mac is arm64),
  `tags: ghcr.io/linked-fw/fuseki:${TAG}`, `push: true`. A `main` merge that changes `server/`
  without a new tag publishes nothing.
- **First publish (at R, owner):** the GHCR package is created private; the owner sets it
  **public** once (package settings → visibility) and links it to the repository if the label did
  not. Recorded in the README's "Releasing the image" and in report 001's follow-ups.

**`@_linked/fuseki` helper** (`src/utils/datasets.ts`, exported through `src/index.ts`'s
`export * from './utils/datasets.js'`): `storedEndpoint(base, dataset)`,
`${trimmed base}/${dataset}/stored` (trailing slashes of
`base` trimmed like `FusekiStore` does), JSDoc saying what each request form returns; existing
signatures unchanged (`createDataset`'s `dbType` stays `tdb2`). Tests in
`src/utils/datasets.test.ts`.

**README** (`README.md`; npm always ships it, whatever `files` says; `lincd-fuseki.README.md`
becomes a one-line pointer to it): "Fuseki store" (the existing env vars, kept); "The image"
(tag, what it adds to `secoresearch/fuseki:6.2.0`, env, a `docker run` and a compose snippet);
"Datasets infer classes" (the template, the assembler snippet for static datasets, classes only,
every graph, lazy rebuild, a delete of an inferred triple is a no-op); "The `ds` dataset";
"Stored-only reads" (`/<ds>/stored`, the helper, why copies, counts and backups use it, `GET
/<ds>` includes inferred quads); "Graphs" (decision 6: the default graph is the stored default
graph, no `unionDefaultGraph`, named graphs persist and are inferred); "Backups and deletion"
(per M3, M4); "SERVICE is off"; "Building the image locally"; "Releasing the image" (`IMAGE_TAG`,
the image workflow, public visibility). Current state only.

### Files (found)

| File | Change |
|---|---|
| `package.json` | none needed: `files` is an allowlist (`lib`, `lincd-fuseki.README.md`, `CHANGELOG.md`), so `server/` and `docs/` are already excluded; Verify's `npm pack --dry-run` proves it |
| `src/utils/datasets.ts` | add the `storedEndpoint` helper (it holds `createDataset`, `ensureDatasetExists`, `clearDataset`, `ensureDatasetsExist`, `clearDatasets`; `dbType` is a free string) |
| `src/index.ts` | none: already `export * from './utils/datasets.js'` |
| `src/utils/datasets.test.ts` | new; `jest.config.cjs` matches `**/src/**/*.test.ts` |
| `src/shapes/FusekiStore.ts` | none: it uses `/sparql` (`:157`), `/update` (`:202`), `/data` (`:110`), all kept by the template |
| `README.md`, `lincd-fuseki.README.md` | as above |
| `.gitignore` | add `server/target/` |
| `.changeset/<name>.md` | minor: the stored-endpoint helper and the image docs |
| `.github/workflows/pr.yml`, `publish.yml` | unchanged (shared `linked-fw/.github` workflows; `publish.yml`'s filename is load-bearing for npm trusted publishing) |
| `.github/workflows/server.yml`, `image.yml` | new, as above |
| `server/pom.xml`, `server/Dockerfile`, `server/.dockerignore`, `server/IMAGE_TAG` | new, as above (`IMAGE_TAG` holds `6.2.0-1`) |
| `server/templates/config-tdb2` | new: the template |
| `server/configuration/assembler.ttl` | new: the `ds` service |
| `server/src/main/java/cm/linked/fuseki/**` | new: the classes in the layout table |
| `server/src/main/resources/META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle` | `cm.linked.fuseki.sys.InitLinkedFuseki` |
| `server/src/main/resources/META-INF/services/org.apache.jena.fuseki.main.sys.FusekiAutoModule` | `cm.linked.fuseki.fuseki.FMod_LinkedFuseki` |
| `server/src/test/java/cm/linked/fuseki/**`, `server/src/test/resources/**` | new: the JUnit tests below and their assemblers |
| `server/scripts/smoke.sh` | new: starts the image as container `lf-smoke-<pid>` on port 3233 (`SMOKE_PORT`), waits for `$/ping`, runs the smoke checks, removes the container (by its own name, trap on exit) |
| `server/probe/compose.yml`, `server/probe/text.ttl`, `server/probe/probe.sh` | new: the measurement rig (compose project `lf-f1-probe`, port 3234, no named volumes) |
| `server/bench/gen.py`, `server/bench/bench.mjs`, `server/bench/conf/*.ttl` | new: the 1M-triple benchmark from ideation (copied from the session scratchpad `rdfs-bench/`, which is ephemeral; `gen.py`'s schema source path becomes an argument; base URL from `BENCH_URL`, default port 3232) |
| `docs/reports/001-store-inference.md` | new: the record |

### Pitfalls

- **Ports and containers.** Never 3030, never the owner's compose project, never
  `docker compose down -v`, never kill by name. Probes: `docker compose -p lf-f1-probe -f
  server/probe/compose.yml up -d --wait` / `... down` (no named volumes, so nothing lingers);
  smoke: its own container name on 3233; bench: 3232.
- **The operation must exist before config parsing.** `FusekiConfig.buildEndpoint` resolves
  `fuseki:operation` with `Operation.get`; an unallocated `lf:stored` fails the template and every
  configuration file. Allocate it in the Jena subsystem (`InitLinkedFuseki`), not only in the
  module. Register the **handler** in `prepare`: the registry is frozen right after it.
- **Graph-level writes bypass dataset wrappers.** TDB2's graphs (`GraphViewSwitchable`) write to
  TDB2 directly; without `TrackingGraph`, a GSP PUT or `CLEAR DEFAULT` that changes the hierarchy
  would leave it stale. Test every write path in the Design list.
- **Lazy rebuild reads inside the beginning transaction.** Rebuild after `super.begin(..)`, never
  in `commit()` (no extra transaction on a thread that has just committed, no writer-lock time,
  and a burst of N commits costs one rebuild). The generation is bumped only **after** TDB2's
  commit returns; a rebuild labels the view with the generation read after its begin, so a race
  can only cause one extra rebuild, never a stale view. A writer's own `subClassOf` change shows in
  its next transaction, not later in the same one (one SPARQL update request that inserts a
  `subClassOf` and then deletes `WHERE { ?x a <Super> }` sees the old hierarchy: document it).
- **Pinning:** the view is pinned per thread at begin and dropped at end; a reader that began
  before a hierarchy change keeps its hierarchy for the whole query. Fuseki runs a request's
  transaction on one thread.
- **`DatasetGraphRDFS` cannot be updated in place** (its `SetupRDFS` is `final`); swap the view
  object. Do not cache `getDefaultGraph()` results across transactions.
- **`isTDB2` and `isBackedByTDB` are `instanceof` checks** on the top dataset: the admin delete
  skips file removal (hence the filter), and the view runs on ARQ's generic engine (as measured in
  decision 9). `/stored` hands the raw TDB2 dataset to the query engine, so it runs on TDB2's.
  `$/compact` unwraps (`ActionCompact.java:79`): M6 confirms.
- **`$/backup` and `GET /<ds>` include inferred triples.** Nothing in the module can change that
  (`ActionBackup` takes the service dataset); the contract moves backups and copies to `/stored`.
- **No `tdb2:unionDefaultGraph`** anywhere (template, `ds`, tests): it does not reach the view and
  hides the stored default graph from reads (decision 6).
- **SERVICE:** the global context setting holds unless a dataset's `ja:context` sets
  `arq:httpServiceAllowed true`; the README says not to.
- **`ENABLE_*` edit `ds` only**, by `sed` on exact commented lines: keep those lines byte for byte
  in `server/configuration/assembler.ttl`, and keep it writable by uid 9008.
- **A persisted `/fuseki-base` volume keeps an old template**: `TemplateGuard` replaces it with a
  WARN; a consumer that mounts its own `configuration/` (CN does) replaces `ds`, which is expected.
- **Run-area statics:** Fuseki's `FusekiServerCtl` paths are static; a test that starts a server
  with a run area (`-DFUSEKI_BASE=<temp>`, read by `Lib.getenv` before the environment) runs in its
  own fork.
- **CI and branch protection:** `main` requires `checks / Build & Test` only; do not make the
  path-filtered `server.yml` required. The image workflow runs only on `main`, so it is first
  exercised at R; F1 runs its steps locally (`docker build`, smoke) instead.
- **Commit messages and comments:** no AI tooling, no Co-Authored-By trailer, no step or phase
  labels in code.

### Architecture

- CN plan 070 §Decisions 2-9 (store, reasoner, classes only, template, `/stored`, graphs, Fuseki
  6.2, the module's home, the benchmark) and §Contracts (above).
- CN `docs/architecture/04-storage-model.md` §Dataset types, §Copy-on-write branch forks,
  §Provisioning interfaces (C1 rewrites them against this step's results).
- CN `docs/architecture/07-infrastructure-and-execution.md` (Fuseki as a shared service).
- Jena's RDFS documentation (`https://jena.apache.org/documentation/rdfs/`) and Fuseki modules
  (`https://jena.apache.org/documentation/fuseki2/fuseki-modules.html`).

### Tests

**JUnit** (`mvn -B -f server verify`), in-memory or temp-directory TDB2
(`DatabaseMgr.createDatasetGraph()` / `DatabaseMgr.connectDatasetGraph(tempDir)`):
- `DatasetGraphRDFSLiveTest`: `a ⊑ b ⊑ c`, `x a a` infers `x a b`, `x a c` in the default graph,
  a named graph and the union graph; the stored dataset holds no inferred triple; each write path
  of the Design list marks the hierarchy and the next transaction sees the change (insert and
  delete); commits without a default-graph `subClassOf` change (other predicates; `subClassOf` in
  a named graph) leave `rebuilds()` unchanged; an aborted write leaves generation and hierarchy
  unchanged; **a burst of 800 commits that each insert one `subClassOf`, then one query: exactly
  one rebuild, and the query sees all 800**; 8 threads beginning reads after a stale commit cause
  one rebuild; a reader pinned before a hierarchy commit keeps the old hierarchy until its end; a
  cycle `a ⊑ b ⊑ a` terminates; `subPropertyOf`, `domain`, `range` in the data infer nothing;
  `DELETE DATA` of an inferred triple changes nothing.
- `AssemblerTest`: `lf:DatasetRDFSLive` over `tdb2:DatasetTDB2` from Turtle; close, expel,
  reopen: the named graph's data and its inference survive (the persistence check); a missing
  `ja:dataset` is an `AssemblerException`; `text:TextDataset` over the type infers and
  `LiveDatasets.stored` unwraps through it.
- `FusekiStoredTest` (embedded `FusekiServer`, port 0, the template's text with `{NAME}` and
  `{FUSEKI_BASE}` substituted, parsed with `parseConfig`): SPARQL over HTTP infers; `/stored`
  query and `GET /stored` (N-Quads, `?default`, `?graph=`) hold no inferred triple; `POST` GSP to
  `/stored` is 405; a query with `SERVICE <http://example.org/sparql>` is refused (status as
  measured, 422 expected); `GET /<ds>` (dataset URL) holds inferred quads (documents the contract
  item).
- `TemplateGuardTest`, `DropDatabaseFilterTest` (unit level: path rules, symlink, outside
  `databases/`, non-live dataset untouched).

**Smoke** (`server/scripts/smoke.sh <image>`, ~40 s): `POST $/datasets` (`dbName=smoke`,
`dbType=tdb2`), insert a hierarchy and an instance, ASK through `/smoke/sparql` true and through
`/smoke/stored` false, `GET /smoke/stored` N-Quads count equals the inserted count, SERVICE
refused, `ds` with `ENABLE_UPDATE=true ENABLE_SHACL=true`: a SHACL shape on the superclass
reports the subclass instance through `/ds/shacl`, `DELETE $/datasets/smoke` and the directory is
gone.

**Quick gate** (after each phase, ~1-2 min): `mvn -B -q -f server verify` (Phase 3 adds
`npx linked build && npm test`).
**Full gate** (before landing, ~6 min): `mvn -B -f server verify`, `docker build -t
linked-fuseki:dev server`, `server/scripts/smoke.sh linked-fuseki:dev`, `npx linked build && npm
test`, `npm pack --dry-run` check; plus `server/probe/probe.sh` once more if the module changed
after Phase 2.

### Measurements (Phase 2: `server/probe/probe.sh`, on the built image, project `lf-f1-probe`, port 3234)

Each prints one line `Mx <name>: <result>`; report 001 quotes them with the proposed final
contract text.

| # | What | How |
|---|---|---|
| M1 | Template dataset infers | `POST $/datasets dbName=m1 dbType=tdb2`; `GET $/datasets/m1`; insert `ex:A ⊑ ex:B`, `ex:a a ex:A`; ASK `ex:a a ex:B` via `/m1/sparql` (true), `/m1/stored` (false) |
| M2 | `stored` request forms | `GET /m1/stored` (N-Quads), `?default`, `?graph=`, `?query=`, POST `application/sparql-query`, POST form `query=`; POST GSP → 405; settles contract item 1 |
| M3 | `$/backup` | `POST $/backup/m1`, poll `$/tasks/<id>`, `docker compose cp` the dump, `zcat \| grep -c` the inferred `ex:a a ex:B` line; settles item 2 |
| M4 | Delete | `DELETE $/datasets/m1`; `docker compose exec fuseki ls /fuseki-base/databases/m1` (gone with the filter; run once with the filter off via `-Dlf.dropFilter=false` to record Fuseki's own behaviour); re-create `m1`: stored count 0; settles item 3 |
| M5 | Text index | `server/probe/text.ttl` (pg-test's shape: `text:TextDataset` → `lf:DatasetRDFSLive` → TDB2, Lucene) mounted in `configuration/`; insert a labelled subclass instance: `text:query` finds it, ASK superclass true, `/stored` has no inferred triple; settles item 4 |
| M6 | Compact | `POST $/compact/m1?deleteOld=true`: status and the stored count after |
| M7 | Restart | named-graph data + hierarchy, `docker compose -p lf-f1-probe restart`: inference and counts unchanged; the log shows one rebuild per dataset, on its first transaction after the restart |
| M8 | SERVICE | a `SERVICE` query: status (422 expected) and Fuseki's log line |
| M9 | Lazy rebuild | 800 sequential `INSERT DATA { ex:Ci rdfs:subClassOf ex:Root }` updates (as K1's first sync), then one ASK `ex:i a ex:Root` for an instance `ex:i a ex:C800`: total time of the burst vs the same burst on a plain TDB2 dataset; the log shows one rebuild and its ms |
| M10 | Benchmark | `server/bench` at ~1M triples (schema.org's 952 `subClassOf`) on `lf:DatasetRDFSLive` vs stock `ja:DatasetRDFS` vs plain TDB2 (Q1-Q9, inserts/deletes); acceptance: within 20 % of decision 9's stock numbers per query; plus a hierarchy change mid-run (rebuild ms; the next query sees it) |
| M11 | Copy round trip | `GET /m1/stored` N-Quads → POST into a new template dataset's URL: stored counts equal, inferred answers equal |
| M12 | `ds` and SHACL | container with `ENABLE_UPDATE=true ENABLE_DATA_WRITE=true ENABLE_SHACL=true`: `/ds` infers, `/ds/stored` stored-only, `/ds/shacl?graph=default` with a shape `sh:targetClass ex:B` reports the `ex:A` instance; without the env vars `update` and `shacl` answer 4xx; text index works |
| M13 | Template guard | a container whose `/fuseki-base/templates/config-tdb2` is the stock file (bind-mounted copy): after start the file is ours and the log has the WARN; a form-created dataset infers |

### Size and phases

**Large, 3 phases** (one commit each).
1. **The module** — Maven project, every class in the layout table, the service files, the
   bundled template, JUnit as listed. Quick gate: `mvn -B -q -f server verify`.
2. **The image and the measurements** — Dockerfile, `.dockerignore`, `IMAGE_TAG`,
   `configuration/assembler.ttl`, `smoke.sh`, the probe rig and `probe.sh`, `server/bench`, the
   two workflows; run M1-M13; fix what they show inside the design (a finding that needs a design
   choice: stop and report); report 001 drafted with the measurements and the final contract text.
   Quick gate: `mvn -B -q -f server verify` + `docker build` + `smoke.sh`.
3. **The TS package and the docs** — helpers and tests, README, `lincd-fuseki.README.md` pointer,
   `.gitignore`, changeset, report 001 finished (Verify output, deviations, follow-ups with their
   homes). Quick gate: `mvn -B -q -f server verify` + `npx linked build && npm test`.
   Then the full gate, the independent review, landing.

## Progress

- [x] F1 — Phase 1: the module and its JUnit tests
- [x] F1 — Phase 2: image, workflows, measurements M1-M13 (report 001 drafted)
- [x] F1 — Phase 3: helpers, README, changeset, report 001
- [ ] F1 — independent review (sensitive step), no open medium or high finding
- [ ] F1 — landed on `store-inference` (commit) and pushed; contract changes handed to the master

## Outcome (F1)

Record: [report 001](../reports/001-store-inference.md) (what was built, M1-M13 quoted, the full
gate with timings, the independent review).

- **Changed:** `server/` (module, 38 JUnit tests, image, smoke, probe rig, benchmark),
  `.github/workflows/server.yml` and `image.yml`, `storedEndpoint` with tests, README, the
  `lincd-fuseki.README.md` pointer, a minor changeset.
- **Verification:** `mvn -B -f server verify` 38 tests BUILD SUCCESS (14.9 s); `docker build`;
  `smoke.sh` 16 ok (3.8 s); `npx linked build && npm test` 12 tests (6.4 s); `npm pack --dry-run |
  grep -c server/` 0; `probe.sh` M1-M13 (3:26). Output quoted in report 001.
- **Settled pending item:** `text:TextDataset` works over `lf:DatasetRDFSLive` (M5, M12).
- **Contract changes proposed** (report 001 §Settled contract items): the rebuild happens at a
  transaction's first read, not at its begin (so write-only bursts cost one rebuild; M9: 800
  commits, 0 rebuilds, 1 at the next read in 18 ms); the hierarchy is built when a dataset opens;
  `$/compact` works; `/<ds>/upload` is not served on Fuseki 6.2 (405, stock image too); `GET
  /<ds>/data` also includes inferred triples.
- **Deviations:** generation read before `begin`; per-thread tracker mark; compose project
  `f1-probe`; upload covered by neither JUnit nor smoke (does not exist on 6.2).
- **Follow-ups:** image publish and public visibility at R (owner); pg-test's
  `fuseki:serviceUpload` in C1; contract text to the master.

