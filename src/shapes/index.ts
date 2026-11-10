/**
 * Registers every shape this package defines, and nothing else.
 *
 * This package currently defines no shapes: `FusekiStore` is a dataset
 * implementation (it extends `SparqlDataset` and no longer carries
 * `@linkedShape`), so it is deliberately not imported here — it also reaches
 * `node:fs`, which a shape registry has no business pulling into a browser
 * bundle. The module exists so that `import '@_linked/fuseki/shapes/index'`
 * resolves the same way it does for every other linked package, and it still
 * registers the ontology. Add a side-effect import here for any shape module
 * added to this folder.
 */
import '../ontologies/index.js';
