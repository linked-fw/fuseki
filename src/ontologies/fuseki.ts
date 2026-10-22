import { createNameSpace } from '@_linked/core/utils/NameSpace';

/**
 * Load the data of this ontology into memory, thus adding the properties of the entities of this ontology to the local graph.
 */
export var loadData = () => {
  //@ts-ignore
  return import('../data/fuseki.json', { with: { type: 'json' } }).then(
    (data) => data.default
  );
};

/**
 * The namespace of this ontology, which can be used to create NamedNodes with URI's not listed in this file.
 *
 * First-party ontologies live on linked.cm: `https://linked.cm/ont/{ontologySlug}/`, and a
 * package's own ontology takes the package's publicSlug (`@_linked/fuseki` → `fuseki`), the same
 * slug its shapes use under `https://linked.cm/shape/fuseki/`.
 *
 * Until this release it was `http://lincd.org/ont/lincd-fuseki/`. Its only term, `FusekiStore`,
 * never types stored data (`FusekiStore` is a dataset class, not a shape), so nothing needs to be
 * migrated.
 */
export var ns = createNameSpace('https://linked.cm/ont/fuseki/');

/**
 * The NamedNode of the ontology itself
 */
export var _self = ns('');

//A list of all the entities (Classes & Properties) of this ontology, each exported as a NamedNode
export var FusekiStore = ns('FusekiStore');
// export var exampleProperty= ns('exampleProperty');

//An extra grouping object so all the entities can be accessed from the prefix/name
export const fuseki = {
  FusekiStore,
  // exampleProperty,
};

