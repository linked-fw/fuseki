---
summary: >
  `FusekiStore` for CN plan 071 (step F2): maps the portable `graphs.default`/`graphs.union` to
  Jena's `urn:x-arq:DefaultGraph`/`urn:x-arq:UnionGraph` (`jenaGraphName`), turns on
  `isolateLinkedTypeChecks`, keys core's graph-registry cache by the dataset endpoint
  (`{baseUrl}/{dataset}`) and keeps core's `urn:x-arq:` write refusal. Unit tests, a live test on
  `linked-fuseki:071` (`.from(graphs.union)`, `graphs.default` alone, `OntologyGraph.iriFor(...)`,
  `graphs.allOntologies`), README §Reads from graphs, minor changeset.
packages: [fuseki]
---

# 003 — `FusekiStore` reads from graphs

Record of step F2 of CN plan 071 (sub-plan [002](../plans/002-named-graphs.md) §F2). Integration
branch `named-graphs`; worktree `~/web/wt/f2` (branch `named-graphs--f2`), built against core's
integration build (K2 landed, core `9ce8b8f`, resolved from the main checkout; fuseki `overlay`).

## What was built

- **`src/shapes/FusekiStore.ts`**: the constructor passes `{isolateLinkedTypeChecks: true,
  mapGraphName: jenaGraphName}` to `SparqlDataset` (store-level; `FusekiStoreConfig` gains no
  field). `jenaGraphName` (exported): `graphs.default` → `urn:x-arq:DefaultGraph`, `graphs.union` →
  `urn:x-arq:UnionGraph`, any other IRI unchanged. `graphRegistryKey()` returns
  `{baseUrl}/{dataset}` (trailing slashes dropped), the same form as core's test store, so every
  store for one dataset (CN's `AppDataFusekiStore` included) shares one cache entry.
  `isReservedGraphName` is not overridden (core's default refuses `urn:x-arq:*`).
- **`FusekiStore.graphs.test.ts`** (unit, 10 tests): the mapping, the options, the key (shared per
  dataset, distinct per dataset and per host), and graph writes to `urn:x-arq:DefaultGraph`,
  `urn:x-arq:UnionGraph`, `graphs.default`, `graphs.union` and `graphs.registry` refused with
  `graph-name-read-only` without a request.
- **`FusekiStore.graphs.live.test.ts`** (live, 5 tests; skipped with a log line unless
  `FUSEKI_LIVE_URL` and `FUSEKI_LIVE_ADMIN_PASSWORD` are set): on its own dataset (deleted after),
  the default graph holds a `Thing` and a `Sub`; `OntologyGraph.create(...).withContent(...)`
  through the store registers `https://linked.cm/pkg/fuseki-live-test/graph/ontology` holding
  `Sub rdfs:subClassOf Thing` and another `Thing`.
- **README** §Fuseki store → §Reads from graphs: `.from()` examples, the name mapping, refused
  writes, the option and why, the registry cache key, image `6.2.0-2` required.
- **Changeset** `.changeset/fuseki-store-graphs.md` (minor) with the breaking changes: needs image
  `6.2.0-2` (or a registry-based store) with the package-graph core; needs core's `.from()` and
  graph API; `isolateLinkedTypeChecks` always on.

## Verification

Own Fuseki `linked-fuseki:071` on :3177 (compose project `fuseki-071-f2`, `ADMIN_PASSWORD` set in
a scratch compose file), env `FUSEKI_LIVE_URL=http://localhost:3177
FUSEKI_LIVE_ADMIN_PASSWORD=…` exported before the first live run.

Quick gate (`npm test`, before the live env): `Test Suites: 4 passed, 4 total`, `Tests: 21
passed, 21 total`, `Time: 2.919 s` (4.2 s wall).

Verify (master §F2 and the step's brief), `src/shapes/FusekiStore.graphs.live.test.ts --verbose`:

```
✓ .from(graphs.union) returns a named-graph triple, not the default graph (21 ms)
✓ .from(graphs.default) alone reads the default graph, inferring through the registered graph (22 ms)
✓ .from(OntologyGraph.iriFor(pkg)) reads that package graph only (18 ms)
✓ .from(graphs.allOntologies) lists the registered graph (47 ms)
✓ a second store for the same dataset shares the registry cache key (36 ms)
```

With `DEBUG_FUSEKI=1` the registry `SELECT ?g ?c …` was sent once for both stores (the second
store's `graphs.allOntologies` read was served from the shared entry). The `graphs.default` read
returns the default graph's `Sub` as a `Thing`: the hierarchy came from the registered package
graph (image `6.2.0-2`).

Full gate:

- `mvn -B -f server verify`: `Tests run: 63, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`,
  `Total time: 17.523 s`.
- `docker build -t linked-fuseki:071-f2 server` (3.4 s, cached): image id
  `sha256:b6ceac24dc9c…`, identical to `linked-fuseki:071` (F2 changes nothing in `server/`; the
  `071` tag the owner's store runs was not retagged; the `071-f2` tag was removed afterwards).
- `SMOKE_PORT=3177 server/scripts/smoke.sh linked-fuseki:071-f2`: 24 `ok`, `smoke: 7 s`.
- `npx linked build && npm test` (live env exported): `✔ Build successful`; `Test Suites: 5
  passed, 5 total`, `Tests: 26 passed, 26 total`, `Time: 3.152 s` (10.6 s wall, build included).
- `lib/esm/shapes/FusekiStore.js` contains `jenaGraphName`, `graphRegistryKey` and
  `isolateLinkedTypeChecks`; no test file in `lib/`.

## Deviations

- `jenaGraphName` returns any other IRI unchanged (sub-plan 002), where core's test store returns
  `null`; core calls the hook only for the two portable names today, so the result is the same.
- The live test also covers `graphs.allOntologies` and the shared cache key, beyond master §F2's
  one union read.

## Contracts

Implemented as written in master §`FusekiStore` and §Reads ("As landed (K2)"): the registry key is
`{baseUrl}/{dataset}`; V1's push must pass that string for a `FusekiStore`-backed dataset. No
contract change.

## Follow-ups

- `@_linked/core`'s range in `package.json` (`^2.37.1`) is raised to the phase-1 core at R1
  (master §R1).
- `graphs.default`/`graphs.union` together with other graphs: core backlog 066 (K4).
