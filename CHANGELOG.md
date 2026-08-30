# @\_linked/fuseki

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
