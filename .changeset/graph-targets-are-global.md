---
'@_linked/fuseki': minor
---

`FusekiStore`'s config no longer takes `ontologyGraph` and `shapeGraph`: `@_linked/core` 2.42 made the graph targets one global setting the host sets (`setGraphTargets`; `@_linked/server` reads `LINKED_ONTOLOGY_GRAPH` / `LINKED_SHAPE_GRAPH`), so no store takes them. The README's "Store config" section is gone with them. Requires `@_linked/core` 2.42.0.

Breaking: the `ontologyGraph` / `shapeGraph` config options are removed. Set the mode with the two environment variables (or `setGraphTargets` in a host of your own) instead.
