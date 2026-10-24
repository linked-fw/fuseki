---
"@_linked/fuseki": minor
---

`FusekiStore`'s config takes `ontologyGraph` and `shapeGraph` (`'package'`, `'default'` or the absolute IRI of one shared graph) and hands them to core, which checks them when the store is built. A value in the store config wins over the environment (`LINKED_ONTOLOGY_GRAPH`, `LINKED_SHAPE_GRAPH`, as filled by `@_linked/server` or `loadStores`), and both default to `'package'`. Requires `@_linked/core` 2.41.0.
