---
'@_linked/fuseki': minor
---

Ontology data: labels, descriptions and types for every term.

- `src/data/fuseki.ttl`, copied into `lib/esm/data/` by the build, is the ontology's data: the
  ontology node (`dcterms:title`, `dcterms:description`, `owl:versionInfo`) and all 1 terms of
  `ontologies/fuseki`, each typed (`owl:Class`, `owl:ObjectProperty` or `owl:DatatypeProperty`) with an
  English `rdfs:label` and `rdfs:comment`, in the deterministic Turtle form.
- `ontologies/fuseki` exports `dataFiles` and `loadData` (read through
  `@_linked/core/ontology/loadOntologyFiles`, server only), and the register module passes both, so
  the ontology sync and the shape check read the data.
- **Breaking:** `src/data/fuseki.json` (an empty graph) is deleted, so `data/fuseki.json` is no longer in
  `lib/esm/`, and `loadData()` resolves to the Turtle text (`string[]`) instead of a JSON-LD document.
