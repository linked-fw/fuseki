---
summary: >
  Fuseki's sub-plan for CN plan 071 (named graphs): steps F1 and F2. F1: `lf:DatasetRDFSLive`
  builds its class hierarchy from the default graph plus every graph the graph registry
  (`https://linked.cm/pkg/core/graph/registry`) marks with `core:feedsHierarchy true`, and marks
  the hierarchy stale on every write that can change that set (registry writes, `subClassOf`
  writes on the default or a listed graph, graph-level writes); image `6.2.0-2`. F2:
  `FusekiStore` turns on `isolateLinkedTypeChecks` and maps the portable default and union graph
  names to Jena's. Supersedes sub-plan 001's graph text.
status: Implementation (F1 done, F2 todo)
packages: [fuseki]
---

# 002 — Named graphs: the hierarchy from the graph registry, `FusekiStore` graph names

Sub-plan of CN plan 071,
[`docs/plans/071-named-graphs-and-graph-routing.md`](https://github.com/Semantu/create-now/blob/named-graphs/docs/plans/071-named-graphs-and-graph-routing.md)
(locally `~/web/create_now/docs/plans/071-named-graphs-and-graph-routing.md`). Its §Decisions
(1-4 and 10 are F1's and F2's design), §Plan choices, §How to use, §Rules and §Contracts come
first; this file adds the plan-mode detail for the fuseki steps. F1 is **sensitive**: an
independent read-only review of the diff runs before it lands. Records: report 002 (F1),
report 003 (F2). Follow-ups: fuseki backlog from 002.

This sub-plan supersedes the graph text of [sub-plan 001](001-store-inference.md) ("its
hierarchy is the closure of the stored `rdfs:subClassOf` triples in the default graph"; "a
`subClassOf` triple in a named graph is not part of the hierarchy"): the hierarchy is now the
default graph's plus every graph the registry marks.

## Contracts (verbatim from plan 071 §Contracts)

### Graph IRIs and vocabulary (provider K1; consumers F1, K3, V1, C1, C2, B1, K5)

- Package graph: `{package IRI}/graph/{kind}`; `kind` ∈ `ontology` (phase 1), `shapes` (phase 2).
  Package IRI per arch-02: `https://linked.cm/pkg/{publicSlug}` or
  `https://{ws}.id.create.now/pkg/{slug}`. `OntologyGraph.iriFor(packageName)` and
  `ShapesGraph.iriFor(packageName)` build them from the registered package.
- Dataset-owned graph: `{DATA_ROOT}/graph/{kind}`: `drafts`, `shapes`, `import/{slug}`.
- Registry: `https://linked.cm/pkg/core/graph/registry` (= `graphs.registry`).
- Portable names (`@_linked/core/ontologies/graphs`): `graphs.default`, `graphs.union`,
  `graphs.allOntologies`, `graphs.allShapes`, `graphs.registry` under `https://linked.cm/pkg/core/graph/`;
  each name matches its IRI: `…/graph/all-ontologies`, `…/graph/all-shapes`, `…/graph/registry`
  (owner, 2026-10-23). Core's own package
  graphs stay `…/graph/ontology` and `…/graph/shapes`.
- Terms, in core's `data/core.ttl` and `ontologies/linked-core.ts` (core namespace
  `https://linked.cm/ont/core/`): classes `core:OntologyGraph`, `core:ShapesGraph`,
  `core:DraftGraph` (each `⊑ sd:NamedGraph`); properties `core:contentHash`,
  `core:feedsHierarchy` (`xsd:boolean`), `core:package` (the npm package name, string),
  `core:version`. Standard terms used: `sd:NamedGraph`, `sd:name`
  (`http://www.w3.org/ns/sparql-service-description#`), `dcterms:modified`, `dcterms:source`.
  Core declares the two `sd:` terms in a small term module (`ontologies/sd.ts`, no data file: core
  does not mirror SD).
- A record's subject is the graph IRI; `sd:name` repeats it.

```turtle
# in GRAPH <https://linked.cm/pkg/core/graph/registry>
<https://linked.cm/pkg/schema/graph/ontology> a core:OntologyGraph ;
  sd:name <https://linked.cm/pkg/schema/graph/ontology> ;
  core:package "@_linked/schema" ; core:version "1.7.0" ;
  core:contentHash "3f9c…" ; core:feedsHierarchy true ;
  dcterms:modified "2026-10-30T10:12:00Z"^^xsd:dateTime ;
  dcterms:source "data/schema.ttl", "data/schema-added.ttl" .
```


### The Fuseki module (provider F1; consumers every store, K1's and K3's `[fuseki]` tests, C1, C2)

- Image `ghcr.io/linked-fw/fuseki:6.2.0-2` (`server/IMAGE_TAG`), same env and template as `6.2.0-1`.
- Hierarchy = closure of `rdfs:subClassOf` in the default graph **plus** every graph `g` with
  `GRAPH <registry> { ?r core:feedsHierarchy true ; sd:name ?g }` (read on the stored graph,
  without inference: the flag is explicit). The rebuild logs the graph count.
- Marking: any write to the registry graph; a `subClassOf` write (or wildcard delete, clear,
  add/remove graph) on the default graph or on a graph in the set from the last build. A graph
  written before its record counts once the record is written. `getGraph` wraps the registry and
  every listed graph in `TrackingGraph` (GSP `PUT`/`POST`/`DELETE` included).
- Write-only transactions still never rebuild; the first reading transaction after a marking
  commit rebuilds once.
- The registry IRI and the three terms are constants in Java (no assembler property, so existing
  datasets' configuration files need no rewrite).

### `FusekiStore` (provider F2; consumers C1, C2, apps)

`FusekiStore` sets `isolateLinkedTypeChecks: true` and maps `graphs.default` →
`urn:x-arq:DefaultGraph`, `graphs.union` → `urn:x-arq:UnionGraph`. README: graphs, the registry,
the image tag. `@_linked/fuseki`'s ontology data gains nothing.


### Reads with `.from()` and the generator (provider K2, extended by K4; consumers F2, C2, C4)

- DSL: `.from(graph | graph[])` on select, count and ask builders; DSL-JSON field `graphs:
  string[]`; IR field `graphs?: string[]` on `IRSelectQuery`, `IRAskQuery`, `IRCountQuery`. Reads
  may carry it over the wire (decision 8).
- The generator per decision 9: one `GRAPH ?gN` block per subject group; the store adds
  `FROM NAMED` for the expanded set; one graph → `GRAPH <iri>`.
- `SparqlOptions` gains `isolateLinkedTypeChecks?: boolean` (default false) and a hook
  `mapGraphName(iri): string | null` (portable name → store name; `null` refuses).
- The store expands `graphs.allOntologies` (and in phase 2 `graphs.allShapes`) from a registry cache
  (owner, 2026-10-23):
  - **per process, per dataset:** one `SELECT ?g` over `graphs.registry` by class, cached for each
    dataset the process reads;
  - **own writes:** a process that changes a graph (any `graphQuery` through its store) updates its
    own cache for that dataset in the same step, so its next read sees the change;
  - **CN-managed apps:** every graph write of an app under CN passes through CN's gateway, so CN
    pushes an invalidation for that dataset to the app over its control channel after the write
    (arch-07 §Host Agent ↔ app control: the app's `/admin/*` routes with `CN_APP_ADMIN_SECRET`).
    C1 sends it, V1 adds the route and drops the cache; core exposes the drop as a public call
    on the store (`invalidateGraphRegistry(dataset?)`, K2) for V1 to use;
  - **backstop:** an entry older than 30 seconds is re-read, for writes that bypass CN (scripts,
    standalone apps, and an app in a Docker container, which has no control channel yet);
  - writes never depend on the cache.
- Test (core, `[fuseki]`): the same queries with `isolateLinkedTypeChecks` on and off return
  identical rows.


## F1 — the hierarchy from the registry (Java, image)

Plan 071 §F1, verbatim:

> **Goal:** the store infers with the hierarchy of the graphs the registry marks plus the default
> graph, and notices every change to that set.
>
> **Scope:** `Hierarchy.read` (registry, then each listed graph via GPOS, plus default);
> `SubClassOfTracker.isHierarchyGraph(g)` replacing `isDefaultOrAny` (volatile set from the last
> build), `getGraph`/`addGraph`/`removeGraph`/`clear`; `TrackingGraph` for the registry and listed
> graphs; the graph count logged per rebuild; `server/IMAGE_TAG` and the Maven version `6.2.0-2`;
> JUnit (`DatasetGraphRDFSLiveTest`, `AssemblerTest`, `FusekiStoredTest`) flipped and extended;
> README; fuseki plan 001's graph text superseded by sub-plan 002.
>
> **Verify:** `mvn -B -f server verify` with tests: a `subClassOf` in a listed graph infers; in an
> unlisted graph does not; writing the record later makes it infer; `feedsHierarchy false` does not;
> GSP `PUT` on a listed graph marks and the next read sees it; dropping the record removes the
> classes; write-only transactions never rebuild; the default graph still feeds. `docker build -t
> linked-fuseki:071 server` and `server/scripts/smoke.sh linked-fuseki:071`; the 1M-triple bench
> rerun (rebuild time with 20 listed graphs).
>

### Design (inside the contract)

- **Constants in `Hierarchy`** (no assembler property): `REGISTRY` =
  `https://linked.cm/pkg/core/graph/registry`, `FEEDS_HIERARCHY` =
  `https://linked.cm/ont/core/feedsHierarchy`, `SD_NAME` =
  `http://www.w3.org/ns/sparql-service-description#name`. The flag counts when its object is an
  `xsd:boolean` literal whose value is true (`true` or `1`); anything else does not.
- **`Hierarchy.read(stored)` returns the vocabulary and the graph set.** In the caller's
  transaction on the stored dataset (no inference): every `?r core:feedsHierarchy ?f` in the
  registry graph with `?f` true, then every IRI `?g` of `?r sd:name ?g`; then the `rdfs:subClassOf`
  triples (IRI or blank object) of the default graph and of each listed graph, `find(g, ANY,
  subClassOf, ANY)` (TDB2 answers it from GPOS). A listed graph with no data adds nothing. The
  registry graph feeds only if a record lists it.
- **`SubClassOfTracker.isHierarchyGraph(g)`** replaces `isDefaultOrAny`: true for a wildcard
  (`null`, `ANY`, a variable), the default graph, the union graph, and every graph in the set of
  the last published build (a `volatile` record holding the set and the generation it was built
  for). `isRegistryOrAny(g)`: a wildcard, the union graph, or the registry. A write marks when
  `isRegistryOrAny(g)` (any predicate), or when `isHierarchyGraph(g)` and the predicate is
  `rdfs:subClassOf` or a wildcard. `addGraph`/`removeGraph` mark for the registry, a wildcard or a
  hierarchy graph; `clear()` marks.
- **No race between a registry change and the set.** The set is from the last build, so a commit
  that adds a record and a later write-only commit that writes `subClassOf` into that graph could
  both happen before any read rebuilds; and a reader that rebuilds concurrently could publish a
  set that lists the graph from a snapshot that misses the second commit. So a named graph
  outside the set also counts while the set is stale: a registry-touching commit records its
  number (taken from the `pending` counter before TDB2's commit releases the writer lock), and the
  set is stale while that number is greater than the generation the published set was built for.
  This only adds marks; it never removes one the contract requires.
- **`TrackingGraph` knows its graph name** and asks the tracker per write (`check(name, p)`), so
  its decision follows the set at write time. `getDefaultGraph`, `getUnionGraph` and
  `getGraph(n)` wrap every graph they hand out (the registry, listed graphs, and graphs that may
  become listed in the same transaction): a GSP `PUT`/`POST`/`DELETE` on a listed graph or on the
  registry takes a graph handle and marks through it.
- **Publishing the set:** the constructor's build and every build `resolve` publishes as
  `current` set the tracker's set; a private build (a writer that touched the hierarchy itself)
  publishes nothing.
- **Log lines:** `hierarchy built (<n> subClassOf, <k> graphs, <ms> ms)` at open and
  `hierarchy rebuilt (<n> subClassOf, <k> graphs, <ms> ms, generation <g>)`; `<k>` counts the
  listed graphs (the default graph not included).
- Write-only transactions still never rebuild (unchanged mechanism: marks bump `pending` and
  `generation` at commit; the first reading transaction resolves the view).

### Files

| File | Change |
|---|---|
| `server/src/main/java/cm/linked/fuseki/rdfs/Hierarchy.java` | constants; `read` returns `Hierarchy.Read(vocab, graphs)` from registry + listed graphs + default |
| `server/src/main/java/cm/linked/fuseki/rdfs/SubClassOfTracker.java` | `isHierarchyGraph`, `isRegistryOrAny`, the published set and the stale rule, registry mark, wrap every graph |
| `server/src/main/java/cm/linked/fuseki/rdfs/TrackingGraph.java` | holds its graph name, checks through the tracker |
| `server/src/main/java/cm/linked/fuseki/rdfs/DatasetGraphRDFSLive.java` | `View` carries the graph count; publishes the set; records registry commits; log lines |
| `server/src/test/java/cm/linked/fuseki/DatasetGraphRDFSLiveTest.java` | flipped and extended (Tests) |
| `server/src/test/java/cm/linked/fuseki/AssemblerTest.java` | a listed graph persists and feeds after reopening |
| `server/src/test/java/cm/linked/fuseki/FusekiStoredTest.java` | GSP on a listed graph and on the registry; the log's graph count |
| `server/scripts/smoke.sh` | a listed graph infers, an unlisted one does not |
| `server/bench/gen.py`, `server/bench/conf/bench.ttl` | a variant with the hierarchy split over 20 listed graphs |
| `server/IMAGE_TAG`, `server/pom.xml` | `6.2.0-2` |
| `README.md` | §Datasets infer classes, §Graphs: the registry, the image tag |
| `docs/plans/001-store-inference.md` | a note that this sub-plan supersedes its graph text |

### Pitfalls

- **Inference reads the registry through the view:** `Hierarchy.read` must read `stored`, never
  the live dataset (the flag is explicit; an inferred `feedsHierarchy` must not count, and reading
  the view would recurse into the view being built).
- **TDB2 inlines `xsd:boolean`** in its node table: compare the literal's value, not its lexical
  form.
- **Graph handles bypass dataset-level overrides** (a `GraphViewSwitchable` writes to TDB2
  directly): every handle the tracker gives out must be a `TrackingGraph`, the registry and named
  graphs included, or a GSP `PUT` on a listed graph goes unnoticed.
- **`DROP SILENT GRAPH <g>`** (core's `graph_replace`) is `removeGraph`: it must mark for a
  listed graph.
- **A graph written before its record** must feed once the record is written: the record write
  marks (registry), and the rebuild reads the graph whole.
- **No rebuild in a write transaction** (GSP included): unchanged; the new checks only mark.
- **Cost:** each rebuild scans GPOS once per listed graph; measure with 20 listed graphs (bench).
- Never port 3030 or 3939; own compose project and port 3172 for probes; never `docker compose
  down -v` except on the own project; never `docker volume prune` or `docker system prune`.

### Tests

`mvn -B -f server verify` (JUnit, embedded TDB2 and an embedded Fuseki):

1. a `subClassOf` in a listed graph infers (in that graph, in other graphs, in the default graph);
2. in an unlisted graph it does not; writing it there does not rebuild;
3. writing the record later makes it infer (a graph written before its record);
4. `feedsHierarchy false` does not; flipping it to true does; a record without `sd:name` adds
   nothing;
5. GSP `PUT`, `POST`, `DELETE` on a listed graph mark and the next read sees it, with no rebuild
   inside the GSP write; a GSP `PUT` of the registry graph marks;
6. dropping the record (and, separately, `DROP GRAPH` of the listed graph) removes the classes;
7. write-only transactions never rebuild (a burst over listed graphs costs one rebuild);
8. the default graph still feeds (every existing default-graph test stays green);
9. the stale rule: a record commit, then a write-only `subClassOf` commit into the newly listed
   graph, then a read, sees both; a concurrent-reader variant;
10. the assembler: a listed graph persists and feeds after reopening;
11. the log line carries the graph count (`rebuilds()` and a `listedGraphs()` accessor).

Then `docker build -t linked-fuseki:071 server`, `SMOKE_PORT=3172 server/scripts/smoke.sh
linked-fuseki:071`, and the 1M-triple bench rerun (rebuild time with 20 listed graphs) on port
3172, compose project `fuseki-071-f1`.

### Gates

- Quick (after each phase): `mvn -B -f server test -Dtest=<Class>`; `npm test`.
- Full (before landing): `mvn -B -f server verify`, `docker build -t linked-fuseki:071 server`,
  `server/scripts/smoke.sh linked-fuseki:071`, `npx linked build && npm test`.

### Phases

1. Hierarchy from the registry: `Hierarchy`, the tracker's set, `TrackingGraph`, the live
   dataset; JUnit flipped and extended (`DatasetGraphRDFSLiveTest`).
2. HTTP and assembler: `AssemblerTest`, `FusekiStoredTest`, smoke, `IMAGE_TAG`/pom `6.2.0-2`,
   README, plan 001 note.
3. Image, bench (20 listed graphs), report 002, review.

## F2 — `FusekiStore` (TS)

Plan 071 §F2, verbatim:

> **Scope:** §`FusekiStore`; changeset. **Verify:** `npx linked build && npm test`; one live test on
> `linked-fuseki:071` that a `.from(graphs.union)` select returns a named-graph triple.
>

### Files

| File | Change |
|---|---|
| `src/shapes/FusekiStore.ts` | pass `{isolateLinkedTypeChecks: true, mapGraphName}` to `SparqlDataset` (K2's `SparqlOptions`); `mapGraphName`: `graphs.default` → `urn:x-arq:DefaultGraph`, `graphs.union` → `urn:x-arq:UnionGraph`, every other IRI unchanged (`graphs.allOntologies` is expanded by core before mapping) |
| `src/shapes/FusekiStore.graphs.test.ts` (new) | the mapping, and the option set |
| a live test (gated on a running `linked-fuseki:071`, port 3177) | a `.from(graphs.union)` select returns a named-graph triple |
| `README.md` | graphs, the registry, the image tag `6.2.0-2` |
| `.changeset/*.md` | minor; lists "requires `ghcr.io/linked-fw/fuseki:6.2.0-2`" |

### Pitfalls

- **`graphs.union` excludes the default graph** on Jena and must never be written: core refuses
  writes to portable names (`graph-name-read-only`); the mapping is for reads.
- `@_linked/fuseki`'s ontology data (`src/data/fuseki.ttl`) gains nothing.
- The option is store-level (constructor), never per query.
- Built against K2's core: rebase on the landed core and check `npx linked doctor resolve` shows
  core as the integration build before testing.

### Tests

`npx linked build && npm test`; the live test on `linked-fuseki:071` (own container on 3177).

## Progress

| Step | Status | Notes |
|---|---|---|
| F1 | done | report 002 |
| F2 | todo | after K2 |

## Outcome (F1)

Record: [report 002](../reports/002-named-graphs-hierarchy-from-the-registry.md) (what was built,
gates with timings, the bench with 20 listed graphs, the review).

- **Changed:** `Hierarchy`, `SubClassOfTracker`, `TrackingGraph`, `DatasetGraphRDFSLive`; JUnit
  (63 tests), smoke (24 checks), bench variant `bench-listed`; `IMAGE_TAG`/pom `6.2.0-2`; README;
  plan 001 note. No npm change, no changeset.
- **Verification:** `mvn -B -f server verify` 63 tests BUILD SUCCESS (19.8 s); `docker build -t
  linked-fuseki:071 server`; `smoke.sh` 24 ok (4.6 s); `npx linked build && npm test` 12 tests
  (6.1 s); bench: rebuild with 20 listed graphs 43 ms and 6 ms, open build 102 ms, counts identical
  to the default-graph hierarchy.
- **Review:** "Verdict: No open medium or high findings."; two lows and two nits fixed.
- **Deviations:** every graph handle wrapped; the stale rule; default/union graph names in records
  ignored (report 002 §Deviations). Contracts unchanged.
- **Follow-ups:** F2; image publish at R1.
