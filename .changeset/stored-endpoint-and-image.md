---
"@_linked/fuseki": minor
---

Add `storedEndpoint(base, dataset)`: the `/<ds>/stored` endpoint of the linked Fuseki image, which reads stored triples only (backups, copies, counts). The README documents the image `ghcr.io/linked-fw/fuseki`, whose TDB2 datasets infer RDFS classes, its template, the `ds` dataset, graphs, backups, deletion and SERVICE.

Behaviour changes for a store moving to the image (from the stock Fuseki 5 image):

- **Fuseki 6.2** (Jena 6.2.0). `/<ds>/upload` answers 405 (Fuseki 6's upload handler comes with its UI module): load files through `/<ds>/data` or N-Quads to `/<ds>`.
- **TDB2 datasets infer RDFS classes** (`dbType=tdb2` and the `ds` dataset; `dbType=mem` stays plain): every query sees the instances of a subclass as instances of its superclasses. The hierarchy is the stored `rdfs:subClassOf` triples of the default graph only; one in a named graph is not part of it.
- **`POST $/backup/<ds>`, `GET /<ds>` and `GET /<ds>/data` include inferred triples**, and a restore or copy made from them stores the inference. Back up and copy through `GET /<ds>/stored` (`Accept: application/n-quads`).
- **SPARQL Update `ADD`, `COPY` and `MOVE` read the source graph through the inferred view** and so store its inferred triples in the target.
- **`SERVICE` is refused** (`arq:httpServiceAllowed false` for the whole server).
- **`DELETE $/datasets/<ds>` removes the files** of a dataset created through the admin API; a re-created name starts empty.
