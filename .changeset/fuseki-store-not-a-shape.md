---
'@_linked/fuseki': minor
---

FusekiStore is no longer a Shape.

`@linkedShape` and `static targetClass` are gone. A dataset is not a metadata
shape: `SparqlDataset` stopped extending `Shape` deliberately (core commit
`0e8c86e`, "datasets are not shapes"), because it used no Shape members and only
did so for a "persist config as linked data" rationale that was never
implemented. `FusekiStore` was the class that migration missed, and the decorator
had become a type error — `typeof FusekiStore` is not assignable to
`typeof Shape`.

Nothing is lost at runtime. The decorator's only effects here were registering a
package export and minting a NodeShape that nothing read; the ontology it pointed
at (`fuseki.FusekiStore`) has an empty `@graph`. Every consumer imports
`FusekiStore` directly by path, so nothing resolved it through the shape registry.

The constructor parameter narrows from `FusekiStoreConfig | string | {id?: string}`
to `FusekiStoreConfig`. The widened form existed only to satisfy the decorator and
already threw at runtime for anything but a config object.
