---
'@_linked/fuseki': patch
---

Point `linkedOntology`'s `dataSource` at the file that actually exists.

It declared `'../data/lincd-fuseki.json'`, but the file on disk is
`src/data/fuseki.json` — which is what the neighbouring `loadData()` has always
imported. So runtime loading was never affected; only the declared path was
wrong.

`dataSource` is stored as `_data` on the package's exports (core
`Package.ts:422`) and describes where an ontology's raw data lives, for tooling
that reads the package tree. Every other first-party ontology package declares a
path that resolves; this was the only one that did not.
