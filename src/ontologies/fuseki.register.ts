/**
 * Registers this ontology.
 *
 * Kept out of `fuseki.ts` because registration needs that module's whole export
 * namespace, and a module cannot import itself once a bundler is involved: Rollup
 * treats a static self-reference as a circular import and elides it, so the binding is
 * undefined at runtime and the consuming app dies at boot with `_this is not
 * defined`. `tsc` preserves it, which is why the pattern survived for as long as
 * packages were built with `tsc` alone.
 *
 * From a sibling module the same import is ordinary and survives.
 */
import * as terms from './fuseki.js';
import {loadData, ns} from './fuseki.js';
import {linkedOntology} from '../package.js';
import {Prefix} from '@_linked/core/utils/Prefix';

/**
 * @deprecated `lincd-fuseki` was this ontology's prefix label before it was aligned with the
 * ontologySlug (`fuseki`). It stays registered as an alias so `lincd-fuseki:FusekiStore` still
 * expands. It is added BEFORE the main registration on purpose: Prefix keeps every prefix for
 * expansion, but compaction uses whichever prefix was added last for a URI, so the order makes
 * compaction emit `fuseki:`.
 */
Prefix.add('lincd-fuseki', ns('').id);

linkedOntology(
  terms,
  ns,
  'fuseki',
  loadData,
  '../data/fuseki.json'
);
