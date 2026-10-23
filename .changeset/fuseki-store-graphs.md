---
"@_linked/fuseki": minor
---

`FusekiStore` reads named graphs through `@_linked/core`'s `.from()`: it maps the portable
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
