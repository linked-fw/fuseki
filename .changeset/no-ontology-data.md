---
'@_linked/fuseki': minor
---

The ontology registers without data. `src/data/fuseki.json` held an empty graph, so it is deleted and
the ontology no longer passes a `loadData`; apps stop logging that its data "is not Turtle text;
skipped".

- **Breaking:** the `loadData` export of `ontologies/fuseki` is removed, and `data/fuseki.json` is no
  longer in `lib/esm/`.
