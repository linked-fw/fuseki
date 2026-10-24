# @_linked/fuseki

A store for `@_linked/core` that talks SPARQL over HTTP to Apache Jena Fuseki, dataset admin
helpers, and the Fuseki **image** every linked app runs: `ghcr.io/linked-fw/fuseki`, whose datasets
infer RDFS classes.

## What the image adds over stock Fuseki

1. **Live class inference on every dataset.** The dataset type `lf:DatasetRDFSLive` infers
   `rdf:type` through `rdfs:subClassOf` at query time, over TDB2. Nothing inferred is written to
   disk.
2. **A hierarchy that rebuilds itself.** The class hierarchy is read from the stored
   `rdfs:subClassOf` triples in the default graph and rebuilt once, at the first read after a
   commit that changed one. Writes never rebuild (GSP writes included), so a burst of commits costs
   one rebuild, and every transaction sees a hierarchy that matches its own snapshot.
3. **Inference over every graph**, named graphs included.
4. **Stored-only reads at `/<ds>/stored`**: SPARQL queries and graph reads over the stored triples
   only, for backups, copies and counts. Writes there answer 405.
5. **New datasets infer by default.** The admin form's template (`POST $/datasets`, `dbType=tdb2`)
   is baked in and declares an inferring dataset; a template guard checks it at start.
6. **SERVICE (federated queries) is off** server-wide.
7. **Deleting a dataset removes its files.** Stock Fuseki leaves a wrapped dataset's TDB2 files
   behind; the image removes them for datasets created through the admin API.
8. **Fuseki 6.2**, with SHACL validation and the Lucene text index working over inferring datasets.
9. **A published image**: `ghcr.io/linked-fw/fuseki:<jena version>-<n>`, multi-arch, immutable
   tags published only from `main`, a digest-pinned base, running as a non-root user, configured
   with the same environment variables as `secoresearch/fuseki`.

Details follow in [The image](#the-image) and the sections after it.

## Fuseki store

The `FusekiStore` sends SPARQL over HTTP to a Fuseki dataset. Configure it with environment
variables:

- `FUSEKI_BASE_URL` (required): e.g. `http://localhost:3030`, or `http://fuseki:3030` in Docker.
  Trailing slashes are trimmed.
- `FUSEKI_DATASET` (required): the dataset name (e.g. `cn-main`).
- `FUSEKI_DEFAULT_GRAPH` (optional): a graph IRI to target; unset, factories fall back to
  `process.env.DATA_ROOT`. On the image's inferring datasets, `rdfs:subClassOf` triples written
  to a named graph are part of the class hierarchy only when the graph registry lists that graph
  (see [Datasets infer classes](#datasets-infer-classes)).
- `FUSEKI_USER`, `FUSEKI_PASSWORD` (optional): basic auth.

Selects go to `/<ds>/sparql`, inserts and updates to `/<ds>/update`, graph reads and writes to
`/<ds>/data`.

### Store config

`new FusekiStore(config)` takes one object, the `config` of a `linked.backend.datasets.json`
entry:

```json
{
  "datasets": {
    "main": {
      "store": "@_linked/fuseki/shapes/FusekiStore",
      "config": {
        "endpoint": "http://localhost:3030/myapp-main",
        "ontologyGraph": "package",
        "shapeGraph": "https://example.org/graphs/shapes"
      }
    }
  }
}
```

- `endpoint` (required): the dataset URL.
- `credentials` (optional): `{username, password}`; unset, `FUSEKI_USER` / `FUSEKI_PASSWORD`.
- `defaultGraph` (optional): unset, `FUSEKI_DEFAULT_GRAPH`.
- `ontologyGraph`, `shapeGraph` (optional): where `@_linked/core`'s ontology and shape syncs
  write and where `graphs.allOntologies` / `graphs.allShapes` read. Each is `'package'` (one graph
  per package), `'default'` (the default graph) or the absolute IRI of one shared graph; see
  "Ontology and shape graphs" in core's README. Core checks them when the store is built: an
  invalid value, or one shared graph for both, throws.

The graph settings are taken in this order: the value in the store config, then the environment
(`LINKED_ONTOLOGY_GRAPH`, `LINKED_SHAPE_GRAPH`, which `@_linked/server` and `loadStores(config,
{graphTargets})` fill into every setting the config leaves unset), then `'package'`. The store
itself never reads those two variables.

### Reads from graphs

`@_linked/core` reads named graphs with `.from(graph | graph[])` on selects, counts and asks, and
writes whole graphs with its graph API (`OntologyGraph.create(...).withContent(...)`,
`NamedGraph.replaceContent`, ...), which keeps each graph's record in the graph registry (see
[The graph registry](#the-graph-registry)). `FusekiStore` sets up both for Jena:

```ts
import {graphs} from '@_linked/core/ontologies/graphs';
import {OntologyGraph} from '@_linked/core/shapes/NamedGraph';

await Person.select((p) => p.name).from(graphs.allOntologies).exec(store); // every registered ontology graph
await Person.select((p) => p.name).from(OntologyGraph.iriFor('@_linked/schema')).exec(store); // one package graph
await Person.select((p) => p.name).from(graphs.union).exec(store); // every named graph
```

- **Portable names:** `graphs.default` is read as Jena's `urn:x-arq:DefaultGraph` and
  `graphs.union` as `urn:x-arq:UnionGraph` (every named graph, without the default graph);
  `jenaGraphName` is the mapping. Either one works alone; together with other graphs core refuses
  the read for now (`unsupported-query-kind`). `graphs.allOntologies` is expanded by core to the
  graphs the registry lists.
- **Writes** to a portable name, to the registry graph itself, or to any `urn:x-arq:` name are
  refused (`graph-name-read-only`) before a request is sent.
- **`isolateLinkedTypeChecks` is on:** for a subject reached through a link, its inferred type check
  goes in a second `GRAPH ?g` block after a block that reads one of its stored triples, because Jena
  reorders the patterns inside one block and would otherwise run the type check in every graph of
  the set. It is a store setting, never per query.
- **The registry cache** that expands `graphs.allOntologies` is kept per process under the dataset
  endpoint (`{baseUrl}/{dataset}`): every `FusekiStore` for one dataset shares it, its own graph
  writes update it, `store.invalidateGraphRegistry()` drops it, and an entry older than 30 seconds
  is re-read.
- **Image:** the hierarchy of a registered ontology graph is read only by
  `ghcr.io/linked-fw/fuseki:6.2.0-2` or later; on `6.2.0-1` classes declared in package graphs do
  not infer.

The dataset helpers (`@_linked/fuseki/utils/datasets`, also exported from the package root):
`datasetExists`, `createDataset` (`dbType: 'tdb2'` creates an inferring dataset on the image),
`ensureDatasetExists`, `ensureDatasetsExist`, `clearDataset`, `clearDatasets`, and
`storedEndpoint` (below).

## The image

`ghcr.io/linked-fw/fuseki:6.2.0-2` (tags `<jena version>-<n>`, immutable) is
`secoresearch/fuseki:6.2.0` plus:

- the module `linked-fuseki-module.jar` in `/javalibs/` (the image's classpath directory):
  the dataset type `lf:DatasetRDFSLive`, the `/stored` endpoint, SERVICE switched off, the
  template guard and the delete filter;
- `/fuseki-base/templates/config-tdb2`, the template `POST $/datasets` uses, declaring an
  inferring dataset;
- an inferring `ds` dataset (`/fuseki-base/configuration/assembler.ttl`).

Everything else is the base image's: the entrypoint and its environment (`ADMIN_PASSWORD`,
`ENABLE_DATA_WRITE`, `ENABLE_UPDATE`, `ENABLE_SHACL`, `QUERY_TIMEOUT`, `USE_SYSTEM_CA_CERTS`),
`FUSEKI_BASE=/fuseki-base`, uid 9008 (group 0), port 3030.

```bash
docker run -d --name fuseki -p 3030:3030 -e ADMIN_PASSWORD=secret ghcr.io/linked-fw/fuseki:6.2.0-2
```

```yaml
services:
  fuseki:
    image: ghcr.io/linked-fw/fuseki:6.2.0-2
    ports: ["3030:3030"]
    environment:
      ADMIN_PASSWORD: ${FUSEKI_ADMIN_PASSWORD}
    volumes:
      - fuseki_databases:/fuseki-base/databases
volumes:
  fuseki_databases:
```

## Datasets infer classes

A dataset created through the admin form (`POST $/datasets` with `dbName` and `dbType=tdb2`)
infers RDFS classes: with `ex:A rdfs:subClassOf ex:B` stored, every `ex:A` instance is also an
`ex:B` instance to every query. `dbType=mem` datasets (`config-mem`) do not infer.

- **Classes only.** The hierarchy is the transitive closure of the stored `rdfs:subClassOf`
  triples of the **default graph** and of **every graph the graph registry lists** (see
  [The graph registry](#the-graph-registry)). `rdfs:subPropertyOf`, `rdfs:domain` and
  `rdfs:range` infer nothing, even when the dataset holds them. A `subClassOf` triple in any other
  named graph (a draft, an import) is not part of the hierarchy.
- **Every graph** is inferred: the default graph, each named graph and the union graph. Inferred
  triples stay in the graph whose data caused them.
- **Nothing is materialized.** Jena's query-time RDFS view (`org.apache.jena.rdfs`) answers
  inferred triples while it reads; TDB2 holds only what was written, and its transactions are
  unchanged. A delete of an inferred triple is a no-op (the store does not hold it).
- **The hierarchy follows the data, lazily.** A commit that may change it only marks the
  hierarchy stale: any write to the registry graph; a write or delete of an `rdfs:subClassOf`
  triple (or a wildcard delete, a clear, a graph added or dropped) on the default graph or on a
  listed graph. The first transaction that reads
  afterwards rebuilds it once (readers starting meanwhile wait); write-only transactions,
  Graph Store Protocol `POST`, `PUT` and `DELETE` on `/<ds>/data` included, never
  rebuild, so a burst of hundreds of ontology commits costs one rebuild (~20 ms for 800 classes,
  under 10 ms for schema.org's 952 axioms). A transaction keeps the hierarchy it first read with
  until it ends, and never reads with one older than its own snapshot (a transaction that waited
  for the writer lock, or began during another's commit, builds its own). It may read with a
  newer one: when another transaction rebuilt after it began, an axiom deleted since is no longer
  inferred while the deleted triple is still visible to it. A writer sees its own `subClassOf`
  change from its next transaction; within the same transaction it usually reads with the
  hierarchy from before its change (so one update request that inserts a `subClassOf` and then
  runs `DELETE WHERE { ?x a ex:Super }` matches without it), and with its change included only
  when the shared hierarchy was already stale at its first read.
- **`ADD`, `COPY` and `MOVE` store inferred triples.** SPARQL Update's `ADD`, `COPY` and
  `MOVE … TO DEFAULT` (or to any graph) read the source graph through the inferred view, so the
  target receives the source's inferred triples as stored ones. To copy only what is stored, read
  the source from `/<ds>/stored` and write that.
- **Every write goes through the dataset.** Only writes through the `lf:DatasetRDFSLive` dataset
  (its service's endpoints) mark the hierarchy stale. A second service or assembler over the same
  TDB2 location, or a tool writing to the database files while the server runs, changes the data
  but not the hierarchy until the next hierarchy change through the dataset or a restart.
- Opening a dataset (server start) builds its hierarchy once; the log says
  `[linked-fuseki] <location>: hierarchy built (<n> subClassOf, <k> graphs, <ms> ms)` (`<k>`:
  the listed graphs, the default graph not counted), and each rebuild
  `hierarchy rebuilt (…, generation <g>)`.

A static dataset declares the type in its assembler:

```turtle
PREFIX ja:   <http://jena.hpl.hp.com/2005/11/Assembler#>
PREFIX tdb2: <http://jena.apache.org/2016/tdb#>
PREFIX lf:   <https://linked.cm/ont/fuseki#>

:ds  a lf:DatasetRDFSLive ; ja:dataset :tdb .
:tdb a tdb2:DatasetTDB2 ; tdb2:location "/fuseki-base/databases/<name>" .
```

A Lucene index wraps it as any dataset: `:text a text:TextDataset ; text:dataset :ds ; text:index …`.
Over a wrapped dataset jena-text commits the Lucene index after TDB2's commit, not inside it (as
over Jena's own `ja:DatasetRDFS`): a crash between the two can leave the index behind the data;
rebuild it with `jena.textindexer` (`$TEXTINDEXER` in the image).

**The template is kept in place.** Fuseki copies its stock templates into `/fuseki-base/templates`
only when they are missing, so a run area persisted from an older image would create plain
datasets. At start the module rewrites `templates/config-tdb2` when it differs from its own, with a
WARN line. Mount databases (`/fuseki-base/databases`) rather than the whole run area.

## The `ds` dataset

The image keeps the base image's `ds` service: `sparql` and a read-only `data` always;
`update` (`ENABLE_UPDATE=true`), read-write `data` (`ENABLE_DATA_WRITE=true`) and `shacl`
(`ENABLE_SHACL=true`) on request; `stored` always. Its dataset is `lf:DatasetRDFSLive` over TDB2
at `/fuseki-base/databases/ds` (no union default graph) with the base image's Lucene index at
`/fuseki-base/databases/lucene`. It is for tests and experiments; nothing should rely on data in
it. A consumer that mounts its own `/fuseki-base/configuration` replaces it.

## Stored-only reads

Every inferring dataset has `/<ds>/stored`, one endpoint over the stored triples only:

| Request | Answer |
|---|---|
| `GET /<ds>/stored` with `Accept: application/n-quads` | the whole stored dataset, every graph |
| `GET /<ds>/stored?default`, `?graph=<iri>` | one stored graph |
| `?query=…`, `POST` `application/sparql-query`, a form `query=` | a SPARQL query over the stored triples |
| any write | 405 |

```ts
import { storedEndpoint } from '@_linked/fuseki';
storedEndpoint('http://localhost:3030', 'cn-main'); // http://localhost:3030/cn-main/stored
```

Use it for whatever must not see inferred triples: **backups, dataset copies (forks), quad
counts**. `GET /<ds>` (the dataset URL) and `GET /<ds>/data` return inferred triples too, and a
copy made from them would store the inference.

## Graphs

The default graph is the stored default graph: no dataset sets `tdb2:unionDefaultGraph` (the RDFS
view does not see it, and where it applies it hides the stored default graph from reads while
writes still land there). Named graphs persist in TDB2 and are inferred like the default graph;
the union graph is `<urn:x-arq:UnionGraph>` (it does not include the default graph; never write
to it).

### The graph registry

Every dataset may hold a registry of its named graphs, the graph
`<https://linked.cm/pkg/core/graph/registry>` (the same IRI in every dataset), described with
SPARQL Service Description plus `@_linked/core`'s terms. `@_linked/core` writes it (each package's
ontology graph `{package IRI}/graph/ontology` and its record in one update); the module only reads
it. A record lists its graph as a **hierarchy source** with `core:feedsHierarchy true`:

```turtle
# in GRAPH <https://linked.cm/pkg/core/graph/registry>
<https://linked.cm/pkg/schema/graph/ontology> a core:OntologyGraph ;
  sd:name <https://linked.cm/pkg/schema/graph/ontology> ;
  core:package "@_linked/schema" ; core:version "1.7.0" ;
  core:contentHash "3f9c…" ; core:feedsHierarchy true ;
  dcterms:modified "2026-10-30T10:12:00Z"^^xsd:dateTime ;
  dcterms:source "data/schema.ttl", "data/schema-added.ttl" .
```

(`core:` is `https://linked.cm/ont/core/`, `sd:` `http://www.w3.org/ns/sparql-service-description#`.)

- The hierarchy is built from the default graph plus every graph `?g` with `?r
  core:feedsHierarchy true ; sd:name ?g` in the registry, read as stored (no inference; the flag
  is an `xsd:boolean`, so `"true"` as a plain string does not count). Drafts and imports are
  recorded with `false` and never feed it.
- A graph written before its record feeds once the record is written. Dropping the record (or the
  graph) removes its classes at the next read.
- The IRIs are fixed in the module: no dataset configuration names them, and datasets created for
  `6.2.0-1` need no change.
- On `6.2.0-1` the hierarchy is the default graph's only: a `@_linked/core` that moves the
  hierarchy into package graphs needs `6.2.0-2` or later.

## Backups and deletion

- **Backups stream `GET /<ds>/stored`** (`Accept: application/n-quads`), and restore by posting
  the N-Quads to a new dataset's URL. `POST $/backup/<ds>` dumps the service's dataset, inferred
  triples included, and a restore of that dump would store them: never use it for an inferring
  dataset.
- **`DELETE $/datasets/<ds>` removes the dataset's files** under `/fuseki-base/databases/<ds>`
  for a dataset created through the admin API (one with its own `configuration/<ds>.ttl`). Fuseki
  skips that for a wrapped dataset; the module completes it under Fuseki's own rule: after a
  successful delete only, that directory only, never a symbolic link. A dataset re-created under
  the same name starts empty. A dataset declared in a shared configuration file (`ds`, or
  datasets in your own `assembler.ttl`) is only unregistered and keeps its files, as on plain
  Fuseki.
- `POST $/compact/<ds>` works as on plain TDB2.

## SERVICE is off

The module sets `arq:httpServiceAllowed false` for the whole server: a query with
`SERVICE <url>` answers 422. Do not set `arq:httpServiceAllowed true` in a dataset's `ja:context`.
SPARQL Update's `LOAD <url>` still fetches `http:` and `file:` IRIs: keep update endpoints behind
authentication or off the public network.

`/stored` reads below every wrapper of the live dataset. Do not put an access-control dataset
(jena-fuseki-access) above an inferring dataset whose service also has `stored`: `/stored` would
bypass it.

## Fuseki 6.2 notes

- `/<ds>/upload` answers 405 ("No Fuseki dispatch") on Fuseki 6.2, in this image as in the base
  image: Fuseki's upload handler comes with its UI module. Post files to `/<ds>/data` (Graph Store
  Protocol) or N-Quads to `/<ds>` instead.

## Building the image locally

```bash
docker build -t linked-fuseki:dev server     # from this repository's root
server/scripts/smoke.sh linked-fuseki:dev    # port 3233 (SMOKE_PORT)
```

or in compose: `build: { context: <path to this repository>/server }` with `image:
linked-fuseki:dev`. The module alone: `mvn -B -f server verify` (Java 21, Maven; Jena 6.2.0 is a
provided dependency). `server/probe/probe.sh` runs the measurements of
[report 001](docs/reports/001-store-inference.md) on a built image; `server/bench/` is the
1M-triple benchmark.

## Releasing the image

The image has its own version, `server/IMAGE_TAG` (`<jena>-<n>`, kept equal to the version in
`server/pom.xml`; the workflows check it). The **Image** workflow publishes
`ghcr.io/linked-fw/fuseki:<IMAGE_TAG>` for `linux/amd64` and `linux/arm64` when a push to `main`
touches `server/`, or on manual dispatch; an existing tag is never overwritten, so a change to
`server/` is published by bumping `IMAGE_TAG`. The **Server** workflow builds, tests and
smoke-tests on every push that touches `server/`. The npm package (changesets, `publish.yml`)
does not include `server/`.

The first publish creates the GHCR package private: set it to **public** once (package settings →
visibility) and link it to this repository if the label did not. Keep it linked to
`linked-fw/fuseki` with Actions access: the workflow reads existing tags through the Packages API,
which answers 404 both for a missing package and for one the workflow may not see, and a 404 reads
as "not published yet".
