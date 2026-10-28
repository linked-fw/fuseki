# @\_linked/fuseki

## 3.9.2

### Patch Changes

- [#90](https://github.com/linked-fw/fuseki/pull/90) [`78125ce`](https://github.com/linked-fw/fuseki/commit/78125ce3fe474df6c8dc5daef81d4d1b2f981f6a) Thanks [@flyon](https://github.com/flyon)! - `package.json` says what this package is in `"linked": {"kind": "library"}` instead of `"linkedPackage": true`, which the CLI and server no longer read.

## 3.9.1

### Patch Changes

- [#81](https://github.com/linked-fw/fuseki/pull/81) [`a76be96`](https://github.com/linked-fw/fuseki/commit/a76be9629bbb3dbc8fec4c64e5b3fedd0030ff3a) Thanks [@flyon](https://github.com/flyon)! - License: MIT (data under its original license)

## 3.9.0

### Minor Changes

- [#79](https://github.com/linked-fw/fuseki/pull/79) [`dfa44f9`](https://github.com/linked-fw/fuseki/commit/dfa44f99d3de3fb4d2f8a8b862a0fdbdd53a05f9) Thanks [@flyon](https://github.com/flyon)! - `FusekiStore`'s config no longer takes `ontologyGraph` and `shapeGraph`: `@_linked/core` 2.42 made the graph targets one global setting the host sets (`setGraphTargets`; `@_linked/server` reads `LINKED_ONTOLOGY_GRAPH` / `LINKED_SHAPE_GRAPH`), so no store takes them. The README's "Store config" section is gone with them. Requires `@_linked/core` 2.42.0.
  
  Breaking: the `ontologyGraph` / `shapeGraph` config options are removed. A value left in a store config is now ignored, not checked: remove it and set the mode with the two environment variables (or `setGraphTargets` in a host of your own). `@_linked/core`'s `loadStores` warns about such an entry.

## 3.8.0

### Minor Changes

- [#77](https://github.com/linked-fw/fuseki/pull/77) [`61a63b6`](https://github.com/linked-fw/fuseki/commit/61a63b6afdb881b9618b47397b35e3c4a544385f) Thanks [@flyon](https://github.com/flyon)! - `FusekiStore`'s config takes `ontologyGraph` and `shapeGraph` (`'package'`, `'default'` or the absolute IRI of one shared graph) and hands them to core, which checks them when the store is built. A value in the store config wins over the environment (`LINKED_ONTOLOGY_GRAPH`, `LINKED_SHAPE_GRAPH`, as filled by `@_linked/server` or `loadStores`), and both default to `'package'`. Requires `@_linked/core` 2.41.0.

## 3.7.0

### Minor Changes

- [#74](https://github.com/linked-fw/fuseki/pull/74) [`5605388`](https://github.com/linked-fw/fuseki/commit/56053885919aaf4a7352ef35688cbbb54f45c411) Thanks [@flyon](https://github.com/flyon)! - `FusekiStore` reads named graphs through `@_linked/core`'s `.from()`: it maps the portable
  `graphs.default` and `graphs.union` names to Jena's `urn:x-arq:DefaultGraph` and
  `urn:x-arq:UnionGraph` (exported as `jenaGraphName`), turns on `isolateLinkedTypeChecks`, and keys
  core's graph-registry cache by the dataset endpoint (`{baseUrl}/{dataset}`), so every store for one
  dataset shares it and `invalidateGraphRegistry()` drops it for all. Graph writes to `urn:x-arq:*`
  names stay refused (core's `isReservedGraphName`). README: reads from graphs.
  
  Breaking changes:
  
  - Requires `ghcr.io/linked-fw/fuseki:6.2.0-2`, or a store that builds its hierarchy from the graph
    registry, together with an `@_linked/core` that writes ontology data into package graphs: on
    `6.2.0-1` classes declared in package graphs do not infer.
  - Requires an `@_linked/core` with `.from()` reads and the graph API (`SparqlOptions.mapGraphName`,
    `isolateLinkedTypeChecks`, `graphRegistryKey()`).
  - Every `FusekiStore` runs with `isolateLinkedTypeChecks: true`: reads across several graphs may
    put a linked subject's type check in a second `GRAPH` block (same rows).

## 3.6.0

### Minor Changes

- [#72](https://github.com/linked-fw/fuseki/pull/72) [`97198f9`](https://github.com/linked-fw/fuseki/commit/97198f999b1dc15e082a7d0c1a6fcc2668e9009d) Thanks [@flyon](https://github.com/flyon)! - Ontology data: labels, descriptions and types for every term.
  
  - `src/data/fuseki.ttl`, copied into `lib/esm/data/` by the build, is the ontology's data: the
    ontology node (`dcterms:title`, `dcterms:description`, `owl:versionInfo`) and every term of
    `ontologies/fuseki`, each typed (`owl:Class`, `owl:ObjectProperty` or `owl:DatatypeProperty`) with an
    English `rdfs:label` and `rdfs:comment`, in the deterministic Turtle form.
  - `ontologies/fuseki` exports `dataFiles` and `loadData` (read through
    `@_linked/core/ontology/loadOntologyFiles`, server only), and the register module passes both, so
    the ontology sync and the shape check read the data.
  - **Breaking:** `src/data/fuseki.json` (an empty graph) is deleted, so `data/fuseki.json` is no longer in
    `lib/esm/`, and `loadData()` resolves to the Turtle text (`string[]`) instead of a JSON-LD document.

## 3.5.0

### Minor Changes

- [#70](https://github.com/linked-fw/fuseki/pull/70) [`25715a5`](https://github.com/linked-fw/fuseki/commit/25715a565030843c29a9b63f9ae91771a29af26c) Thanks [@flyon](https://github.com/flyon)! - Add `storedEndpoint(base, dataset)`: the `/<ds>/stored` endpoint of the linked Fuseki image, which reads stored triples only (backups, copies, counts). The README documents the image `ghcr.io/linked-fw/fuseki`, whose TDB2 datasets infer RDFS classes, its template, the `ds` dataset, graphs, backups, deletion and SERVICE.
  
  Behaviour changes for a store moving to the image (from the stock Fuseki 5 image):
  
  - **Fuseki 6.2** (Jena 6.2.0). `/<ds>/upload` answers 405 (Fuseki 6's upload handler comes with its UI module): load files through `/<ds>/data` or N-Quads to `/<ds>`.
  - **TDB2 datasets infer RDFS classes** (`dbType=tdb2` and the `ds` dataset; `dbType=mem` stays plain): every query sees the instances of a subclass as instances of its superclasses. The hierarchy is the stored `rdfs:subClassOf` triples of the default graph only; one in a named graph is not part of it.
  - **`POST $/backup/<ds>`, `GET /<ds>` and `GET /<ds>/data` include inferred triples**, and a restore or copy made from them stores the inference. Back up and copy through `GET /<ds>/stored` (`Accept: application/n-quads`).
  - **SPARQL Update `ADD`, `COPY` and `MOVE` read the source graph through the inferred view** and so store its inferred triples in the target.
  - **`SERVICE` is refused** (`arq:httpServiceAllowed false` for the whole server).
  - **`DELETE $/datasets/<ds>` removes the files** of a dataset created through the admin API; a re-created name starts empty.

## 3.4.2

### Patch Changes

- [#63](https://github.com/linked-fw/fuseki/pull/63) [`7e8ed2a`](https://github.com/linked-fw/fuseki/commit/7e8ed2afd54dd72d39141f2dc5b26249a84b9df8) Thanks [@flyon](https://github.com/flyon)! - Build with `linked build`, the standard build for linked packages. The published `lib/` holds the same files as before; the `rimraf` and `copyfiles` dev dependencies are gone.

## 3.4.1

### Patch Changes

- [#61](https://github.com/linked-fw/fuseki/pull/61) [`7e4f8fd`](https://github.com/linked-fw/fuseki/commit/7e4f8fdca9b5e95ff3eb4bac1c85c5f2e5e9980c) Thanks [@flyon](https://github.com/flyon)! - Publish only the files consumers need; the tarball no longer includes `.changeset/`, `.env-cmdrc.json`, `.gitattributes`, `.github/`, `jest.config.cjs`, `renovate.json` or tsconfig files.

## 3.4.0

### Minor Changes

- [#53](https://github.com/linked-fw/fuseki/pull/53) [`c962388`](https://github.com/linked-fw/fuseki/commit/c962388ee256a3c179666bd2a7174dbcbddda230) Thanks [@flyon](https://github.com/flyon)! - The fuseki ontology's prefix label is now `fuseki`, matching its ontologySlug and the `ontologies/fuseki` module (`https://linked.cm/ont/{ontologySlug}/`, with the prefix equal to the slug). It was `lincd-fuseki`. The change covers the `linkedOntology` registration and the `@context` key in `data/fuseki.json`, so prefixed names compact to `fuseki:FusekiStore` and the ontology's exports sit under `fuseki` in the package tree.
  
  No IRI changes: the namespace stays `https://linked.cm/ont/fuseki/`. `lincd-fuseki` is deprecated but still registered as an alias, so `lincd-fuseki:FusekiStore` keeps expanding; it is never emitted. Code that looks the ontology up in the package tree under `lincd-fuseki` must switch to `fuseki`. Stored data under the old IRIs is not migrated; clear dev datasets if any still hold them.

## 3.3.0

### Minor Changes

- [#51](https://github.com/linked-fw/fuseki/pull/51) [`3597388`](https://github.com/linked-fw/fuseki/commit/35973882269d93a1784865be736ac3140bc25380) Thanks [@flyon](https://github.com/flyon)! - The fuseki ontology moves from `http://lincd.org/ont/lincd-fuseki/` to `https://linked.cm/ont/fuseki/`, the first-party scheme every public package uses (`https://linked.cm/ont/{publicSlug}/`, next to its shapes at `https://linked.cm/shape/fuseki/`).
  
  No data migration is needed. The ontology's only term, `fuseki.FusekiStore`, never types stored data: `FusekiStore` is a dataset class, not a shape, so nothing is written under the namespace. The prefix key (`lincd-fuseki`) and the `ontologies/fuseki` module are unchanged. Code that hard-codes `http://lincd.org/ont/lincd-fuseki/` must be updated.
  
  A dataset synced by an older release may still hold a stale `https://linked.cm/shape/fuseki/FusekiStore` shape description (from when `FusekiStore` was a shape) whose `sh:targetClass` is the old IRI. Boot sync no longer prunes shapes that are not in code, so it stays until removed; it describes nothing and nothing reads it.

## 3.2.3

### Patch Changes

- [#44](https://github.com/linked-fw/fuseki/pull/44) [`a55808d`](https://github.com/linked-fw/fuseki/commit/a55808ddf205f11603e8081e5ed64e407d2f0a41) Thanks [@flyon](https://github.com/flyon)! - Add `shapes/index`, the side-effect-only module that registers this package's shapes, so `import '@_linked/fuseki/shapes/index'` resolves like it does for every other linked package. The package defines no shapes today (`FusekiStore` is a dataset, not a `@linkedShape`), so it registers only the ontology.

## 3.2.2

### Patch Changes

- [#36](https://github.com/linked-fw/fuseki/pull/36) [`613ae40`](https://github.com/linked-fw/fuseki/commit/613ae403215d72659a09ee8bca1f4c1c00bb4596) Thanks [@flyon](https://github.com/flyon)! - Sourcemaps now embed their TypeScript source, so consumers no longer see 'points to missing source files' warnings.

## 3.2.1

### Patch Changes

- [#33](https://github.com/linked-fw/fuseki/pull/33) [`30c2710`](https://github.com/linked-fw/fuseki/commit/30c27104d9b3f64ad807b64bee81ca85ee91c626) Thanks [@flyon](https://github.com/flyon)! - Test tooling: `@testing-library/react` moves from v14 to v16.3.3.

  v14 peers on React 18 while the package develops against React 19, so npm could
  not resolve the dependency tree at all — installs here required
  `--legacy-peer-deps`, and the committed lockfile had been written under that
  relaxation (React 19.3.0 and testing-library 14.3.1 side by side). v16.1.0 is
  the first release whose peers accept React 19; we take the current 16.3.3, which
  is what `@_linked/react` already uses. The lockfile now resolves with plain
  `npm install`. No published code changes — devDependency only.

## 3.2.0

### Minor Changes

- [#31](https://github.com/linked-fw/fuseki/pull/31) [`5060287`](https://github.com/linked-fw/fuseki/commit/50602879f8c939ad9418d0937fae96ee801f170c) Thanks [@flyon](https://github.com/flyon)! - Require `@_linked/core@^2.22.8` (was `^2.21.0`), and pin it in the lockfile.

  The declared range was wide enough that the resolved core depended on whatever the
  consumer — or this repo's own CI, via `package-lock.json` — happened to install. Core
  decides how a shape's IRI is minted, so a stale core made this package emit legacy
  `data.lincd.org` IRIs instead of the arch-02 `linked.cm` scheme. Which IRIs a published
  package produces should not be a function of the installer's dependency tree.

  Minor rather than patch: this raises the minimum core a consumer must resolve, so it
  changes what gets installed rather than only what this package does internally.

## 3.1.3

### Patch Changes

- [#29](https://github.com/linked-fw/fuseki/pull/29) [`50180c8`](https://github.com/linked-fw/fuseki/commit/50180c82dc8797231b744323ea5549370a18b3dd) Thanks [@flyon](https://github.com/flyon)! - The ontology no longer registers by importing itself.

  It carried `import * as _this from './<prefix>.js'` and passed that namespace to
  `linkedOntology()`. Under `tsc` the self-reference survives; under a bundler it does
  not — Rollup treats it as a circular import and elides it, so the binding is
  `undefined` and a consuming app dies at boot with `_this is not defined`.

  Registration now lives in a `<prefix>.register.ts` sibling, imported from the package
  entry. Nothing changes for consumers: importing this package still registers the
  ontology.

## 3.1.2

### Patch Changes

- [#27](https://github.com/linked-fw/fuseki/pull/27) [`21dfea5`](https://github.com/linked-fw/fuseki/commit/21dfea574249e4f4a9569ddb3c837dfa1787e37f) Thanks [@flyon](https://github.com/flyon)! - Compile the whole `src` folder, and let a bare import resolve under Node10.

  The build only emitted what an entry transitively reached, so any module
  nothing imported was never built — and never type-checked, so it rotted
  quietly. `include` now covers `src/**/*` with tests excluded explicitly.

  `typesVersions` maps every specifier through `lib/esm/*`, so a `types` value
  that already carried that prefix had it applied twice and no consumer on
  classic Node10 resolution could `import` the package by its bare name.

## 3.1.1

### Patch Changes

- [#25](https://github.com/linked-fw/fuseki/pull/25) [`5e40798`](https://github.com/linked-fw/fuseki/commit/5e4079864ed10105d4b49ff3c968769873d0b5c0) Thanks [@flyon](https://github.com/flyon)! - Point `linkedOntology`'s `dataSource` at the file that actually exists.

  It declared `'../data/lincd-fuseki.json'`, but the file on disk is
  `src/data/fuseki.json` — which is what the neighbouring `loadData()` has always
  imported. So runtime loading was never affected; only the declared path was
  wrong.

  `dataSource` is stored as `_data` on the package's exports (core
  `Package.ts:422`) and describes where an ontology's raw data lives, for tooling
  that reads the package tree. Every other first-party ontology package declares a
  path that resolves; this was the only one that did not.

## 3.1.0

### Minor Changes

- [#23](https://github.com/linked-fw/fuseki/pull/23) [`c051518`](https://github.com/linked-fw/fuseki/commit/c0515185248627b828b4bf67cda71506c0c81c37) Thanks [@flyon](https://github.com/flyon)! - FusekiStore is no longer a Shape.

  `@linkedShape` and `static targetClass` are gone. A dataset is not a metadata
  shape: `SparqlDataset` stopped extending `Shape` deliberately (core commit
  `0e8c86e`, "datasets are not shapes"), because it used no Shape members and only
  did so for a "persist config as linked data" rationale that was never
  implemented. `FusekiStore` was the class that migration missed, and the decorator
  had become a type error — `typeof FusekiStore` is not assignable to
  `typeof Shape`.

  Nothing is lost at runtime. The decorator's only effects here were registering a
  package export and minting a NodeShape that nothing read; the ontology it pointed
  at (`fuseki.FusekiStore`) has an empty `@graph`. Every consumer imports
  `FusekiStore` directly by path, so nothing resolved it through the shape registry.

  The constructor parameter narrows from `FusekiStoreConfig | string | {id?: string}`
  to `FusekiStoreConfig`. The widened form existed only to satisfy the decorator and
  already threw at runtime for anything but a config object.

## 3.0.1

### Patch Changes

- [#21](https://github.com/linked-fw/fuseki/pull/21) [`a765d0d`](https://github.com/linked-fw/fuseki/commit/a765d0d300c4b2272360e5489d4fd6e36ab33a34) Thanks [@flyon](https://github.com/flyon)! - Declare npm as the package manager for this repo, convert the build scripts off `yarn`, and mark `package-lock.json` as a generated file.

## 3.0.0

### Major Changes

- [`85149e4`](https://github.com/linked-cm/fuseki/commit/85149e4695cd18b1ac719d542d19045be663ba6e) Thanks [@flyon](https://github.com/flyon)! - A failed Fuseki request now throws `FusekiQueryError` instead of being swallowed.

  `executeSparqlSelect` returned an empty result set on any non-JSON response — the comment read
  "so callers don't crash" — and never checked the HTTP status at all. So a 404, a 500, an HTML
  error page and a malformed query were indistinguishable from "no rows": every read through this
  store answered "nothing found" when the store was broken.

  `executeSparqlUpdate` checked the status but only `console.warn`ed, so a write that never landed
  reported success.

  **Breaking:** callers that relied on a failed query resolving to an empty result set will now see
  a rejection. That is the point — the old behaviour is why a Create Now existence check reported
  `false` unconditionally in a running backend, sending every re-save down the `create` branch
  instead of `update`, unnoticed.

  `FusekiQueryError` carries `endpoint`, `status`, `body` and `sparql`. The body is where the
  diagnosis usually is: a SPARQL parse error names its line and column.

## 2.0.4

### Patch Changes

- [#12](https://github.com/linked-cm/fuseki/pull/12) [`eb0281e`](https://github.com/linked-cm/fuseki/commit/eb0281e20575bfeb5be2eb79347b1b97dc3fa49c) Thanks [@flyon](https://github.com/flyon)! - Remove the `development` export condition (pointed at `src`, which isn't shipped to npm). Monorepo dev resolves workspace source via the cli Vite plugin; standalone resolves `import → lib`. No consumer-visible change.

## 2.0.3

### Patch Changes

- [#9](https://github.com/linked-cm/fuseki/pull/9) [`0b88d19`](https://github.com/linked-cm/fuseki/commit/0b88d197eabd999e6d2fd45bb8804b456f175552) Thanks [@flyon](https://github.com/flyon)! - loadData: ESM-only JSON import — drop the dead CJS branch, add the `{ with: { type: 'json' } }` import attribute.

## 2.0.2

### Patch Changes

- [#8](https://github.com/linked-cm/fuseki/pull/8) [`dedcef5`](https://github.com/linked-cm/fuseki/commit/dedcef54276e0f5659c5550b046accfe351e46b6) Thanks [@flyon](https://github.com/flyon)! - ESM-only — drops the CommonJS build (`type: module`, no `require` export condition, no `lib/cjs`); type-only imports; ESM-safe. Fixes root `types` field.

## 2.0.1

### Patch Changes

- [#5](https://github.com/linked-cm/fuseki/pull/5) [`ab63a6e`](https://github.com/linked-cm/fuseki/commit/ab63a6e18b1d75914495d5368e30851c2827869c) Thanks [@flyon](https://github.com/flyon)! - Verbose SPARQL query / update / importData console logs are off by default. Set `DEBUG_FUSEKI=1` in the environment to bring them back when debugging. Error/warn output is unchanged.

## 2.0.0

### Major Changes

- [#3](https://github.com/linked-cm/fuseki/pull/3) [`ca4caf8`](https://github.com/linked-cm/fuseki/commit/ca4caf8f118ef2d7cd78fa9a19e02163521deb1b) Thanks [@flyon](https://github.com/flyon)! - `FusekiStore` now takes a config object: `new FusekiStore({ name: 'appData', endpoint: '...' })` (was positional args). Also tracks `SparqlStore` → `SparqlDataset` rename in `@_linked/core`.

  Build pipeline is now explicit per-step (`rimraf && build-esm && build-cjs && copy-to-lib && dual-package`) so silent build failures no longer ship empty tarballs.

## 1.0.2

### Patch Changes

- [`cebcac8`](https://github.com/linked-cm/fuseki/commit/cebcac88c5fef826a7c22221cb045710105db61d) - Initial release under the new publishing setup.
