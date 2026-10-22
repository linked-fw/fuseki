# @_linked/fuseki

A store for `@_linked/core` that talks SPARQL over HTTP to Apache Jena Fuseki, dataset admin
helpers, and the Fuseki **image** every linked app runs: `ghcr.io/linked-fw/fuseki`, whose datasets
infer RDFS classes.

## Fuseki store

The `FusekiStore` sends SPARQL over HTTP to a Fuseki dataset. Configure it with environment
variables:

- `FUSEKI_BASE_URL` (required): e.g. `http://localhost:3030`, or `http://fuseki:3030` in Docker.
  Trailing slashes are trimmed.
- `FUSEKI_DATASET` (required): the dataset name (e.g. `cn-main`).
- `FUSEKI_DEFAULT_GRAPH` (optional): a graph IRI to target; unset, factories fall back to
  `process.env.DATA_ROOT`. On the image's inferring datasets, `rdfs:subClassOf` triples written
  to a named graph are not part of the class hierarchy (only the default graph's are; see
  [Datasets infer classes](#datasets-infer-classes)): write ontology data to the default graph.
- `FUSEKI_USER`, `FUSEKI_PASSWORD` (optional): basic auth.

Selects go to `/<ds>/sparql`, inserts and updates to `/<ds>/update`, graph reads and writes to
`/<ds>/data`.

The dataset helpers (`@_linked/fuseki/utils/datasets`, also exported from the package root):
`datasetExists`, `createDataset` (`dbType: 'tdb2'` creates an inferring dataset on the image),
`ensureDatasetExists`, `ensureDatasetsExist`, `clearDataset`, `clearDatasets`, and
`storedEndpoint` (below).

## The image

`ghcr.io/linked-fw/fuseki:6.2.0-1` (tags `<jena version>-<n>`, immutable) is
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
docker run -d --name fuseki -p 3030:3030 -e ADMIN_PASSWORD=secret ghcr.io/linked-fw/fuseki:6.2.0-1
```

```yaml
services:
  fuseki:
    image: ghcr.io/linked-fw/fuseki:6.2.0-1
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
  triples of the **default graph**. `rdfs:subPropertyOf`, `rdfs:domain` and `rdfs:range` infer
  nothing, even when the dataset holds them. A `subClassOf` triple in a named graph is not part of
  the hierarchy.
- **Every graph** is inferred: the default graph, each named graph and the union graph. Inferred
  triples stay in the graph whose data caused them.
- **Nothing is materialized.** Jena's query-time RDFS view (`org.apache.jena.rdfs`) answers
  inferred triples while it reads; TDB2 holds only what was written, and its transactions are
  unchanged. A delete of an inferred triple is a no-op (the store does not hold it).
- **The hierarchy follows the data, lazily.** A commit that writes or deletes a default-graph
  `rdfs:subClassOf` triple only marks the hierarchy stale. The first transaction that reads
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
  `[linked-fuseki] <location>: hierarchy built (<n> subClassOf, <ms> ms)`, and each rebuild
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
the union graph is `<urn:x-arq:UnionGraph>`.

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
