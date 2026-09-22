# @\_linked/fuseki

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
