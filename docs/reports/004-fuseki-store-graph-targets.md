---
summary: >
  `FusekiStoreConfig` takes `ontologyGraph` and `shapeGraph` and hands them to `SparqlDataset`,
  which checks them with core's `parseGraphTargetOptions`. Precedence: store config, then the
  environment as filled by `@_linked/server` / `loadStores`, then `'package'`. Core raised to
  ^2.41.0, README §Store config, unit tests, minor changeset.
packages: [fuseki]
---

# 004 — `FusekiStore` takes the graph targets

## PR

### Summary

Core 2.41.0 (core report 057) added the store options `ontologyGraph` and `shapeGraph`
(`'package'`, `'default'` or the absolute IRI of one shared graph); server 3.11.0 (server report
017) fills them from `LINKED_ONTOLOGY_GRAPH` / `LINKED_SHAPE_GRAPH`. `FusekiStore`'s own config
did not accept them, so a store built with `new FusekiStore({...})` could only get them from the
environment or through `loadStores`.

- `FusekiStoreConfig` gains `ontologyGraph?` and `shapeGraph?` (typed as core's `GraphTarget`).
  The constructor passes them to `SparqlDataset`, whose constructor checks them with core's
  `parseGraphTargetOptions`: an invalid value, or one shared graph for both, throws when the
  store is built. Nothing is validated twice.
- Precedence: a value in the store config wins; the host then fills the unset ones from the
  environment (`setGraphTargets(..., {onlyUnset: true})`, which server and `loadStores(config,
  {graphTargets})` both use); unset everywhere, `'package'`. The store never reads the two
  variables itself, as core does not.
- README: new "Store config" section (the `config` object, the four fields, the precedence).
- `@_linked/core` raised to `^2.41.0`; the lockfile was refreshed in a scratch copy
  (`npm install --package-lock-only`), changing only the core entry (2.38.0 → 2.41.0, registry
  URL and integrity).
- Changeset `fuseki-store-graph-targets.md` (minor).

### Changes

- `src/shapes/FusekiStore.ts`: config fields, constructor pass-through (`isConfig` guard, so a
  missing endpoint still throws the existing message).
- `src/shapes/FusekiStore.graphTargets.test.ts` (new, 13 tests): default; config value; env value;
  both set (config wins); partial fill; empty config value is unset; invalid values refused at
  build; one shared graph for both refused, from config and from a config/env mix; missing
  endpoint; `loadStores` with the entry config, and with env defaults (entry config wins).
- `README.md`, `package.json`, `package-lock.json`, `.changeset/fuseki-store-graph-targets.md`.

### Verification

Worktree `~/web/wt/fsset` (branch `main--fsset`), core resolved from the main checkout
(2.41.0). Own Fuseki `ghcr.io/linked-fw/fuseki:6.2.0-2` on :3321 (compose project
`fuseki-fsset`).

- `npm test`: `Test Suites: 5 passed, 1 skipped` / `Tests: 5 skipped, 34 passed, 39 total`; with
  `FUSEKI_LIVE_URL=http://localhost:3321 FUSEKI_LIVE_ADMIN_PASSWORD=admin`: `Test Suites: 6
  passed, 6 total` / `Tests: 39 passed, 39 total`.
- `npx linked build`: `✔ Build successful`.
- Independent review: no high or medium findings. Lows applied: `GraphTarget` for the field types,
  one `isConfig` call, a test for a config/env shared-graph clash.

### Deviations

None.

### Follow-ups

- A bad `config.ontologyGraph` in a datasets file now fails inside the store constructor, so the
  error no longer names the alias (`loadStores` used to check first and name
  `datasets.<alias>.config.ontologyGraph`). The fix belongs in core's `loadStores` (prefix
  constructor errors with the alias); recorded in core's `docs/backlog/` with the core change of
  the same session.
