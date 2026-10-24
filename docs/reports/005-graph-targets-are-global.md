---
summary: >
  Reverts 3.8.0's `FusekiStore` config options `ontologyGraph` / `shapeGraph` and the README's
  "Store config" section: `@_linked/core` 2.42 made the graph targets one global setting the host
  sets, so no store takes them. Core raised to ^2.42.0, minor changeset (breaking: the options).
packages: [fuseki]
---

# 005 — The graph targets are not a store setting

## PR

### Summary

The owner's decisions of 2026-10-24 (second round) made the graph targets (`ontologyGraph`,
`shapeGraph`) one global setting of the process: `@_linked/core` 2.42.0 (core report 075) has
`setGraphTargets` / `getGraphTargets` and removed `SparqlOptions.ontologyGraph` / `.shapeGraph`,
and `@_linked/server` sets it from `LINKED_ONTOLOGY_GRAPH` / `LINKED_SHAPE_GRAPH` (server report
018). Report 004's store option has nothing left to feed, so it is reverted:

- `FusekiStoreConfig` loses `ontologyGraph` and `shapeGraph`; the constructor passes only
  `isolateLinkedTypeChecks` and `mapGraphName` to `SparqlDataset` again, and checks the config as
  before 3.8.0.
- `FusekiStore.graphTargets.test.ts` is removed with them.
- README: the "Store config" section 3.8.0 added is removed.
- `@_linked/core` raised to `^2.42.0`; the lockfile was refreshed in a scratch copy
  (`npm install --package-lock-only`), changing only the core entry (2.41.0 → 2.42.0).
- Changeset `graph-targets-are-global.md` (minor; the removed options are listed as breaking).

### Changes

- `git revert` of 61a63b6 for `src/shapes/FusekiStore.ts`, `src/shapes/FusekiStore.graphTargets.test.ts`
  and `README.md` (report 004 stays as history; `package.json` / the lockfile move to core 2.42).
- `.changeset/graph-targets-are-global.md`, `package.json`, `package-lock.json`.

### Verification

Worktree `gglobal`, core resolved from the main checkout (2.42.0, pulled and built). Own Fuseki
`ghcr.io/linked-fw/fuseki:6.2.0-2` on :3322.

- `npx linked build`: `✔ Build successful`.
- `npm test`: `Test Suites: 1 skipped, 4 passed, 4 of 5 total` / `Tests: 5 skipped, 21 passed, 26
  total`; with `FUSEKI_LIVE_URL=http://localhost:3322 FUSEKI_LIVE_ADMIN_PASSWORD=admin`: `Test
  Suites: 5 passed, 5 total` / `Tests: 26 passed, 26 total`.
- `grep -rn "ontologyGraph\|shapeGraph\|graphTargets" src README.md`: no match.

### Deviations

None.

### Review

An independent read-only review found no high or medium finding. Low, applied: the changeset says
a value left in a store config is ignored, not checked (core's `loadStores` warns about it); report
004's follow-up is noted as moot below.

### Follow-ups

- None in this repo. Report 004's follow-up (`loadStores` naming the alias of a bad
  `config.ontologyGraph`) is moot: core no longer checks a store's graph targets (core backlog 074
  keeps only the general constructor-error case).
