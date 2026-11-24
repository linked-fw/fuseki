---
"@_linked/fuseki": major
---

The ontology is registered by `src/ontologies/index.ts` (core's `registerOntologies`), which the package entry imports; the package registers no prefix (an app's prefix map, `linked.app.json`, holds the prefixes; the ontology's data suggests one). `src/package.ts` exports `registerOntologies` instead of `linkedOntology`. Requires the `@_linked/core` major with `registerOntologies`. Removed: `ontologies/fuseki.register`, the `dataFiles` and `loadData` exports of `ontologies/fuseki`, and the deprecated `lincd-fuseki` prefix.
