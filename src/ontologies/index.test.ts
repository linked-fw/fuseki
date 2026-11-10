/**
 * The index registers the ontology under its name `fuseki`, with the suggested prefix its data
 * states, and adds no prefix: an app's prefix map holds the prefixes.
 */
import { Prefix } from '@_linked/core/utils/Prefix';
import { getRegisteredOntologies, suggestedPrefixes } from '@_linked/core/utils/Package';
import { FusekiStore } from './fuseki.js';
import './index.js';

const NS = 'https://linked.cm/ont/fuseki/';

test('the index registers the ontology `fuseki` with its suggested prefix', () => {
  const registration = getRegisteredOntologies().find((r) => r.packageName === '@_linked/fuseki');
  expect(registration?.name).toBe('fuseki');
  expect(registration?.namespace).toBe(NS);
  expect(registration?.files).toEqual(['../data/fuseki.ttl']);
  expect(suggestedPrefixes().filter((p) => p.packageName === '@_linked/fuseki')).toEqual([
    { packageName: '@_linked/fuseki', name: 'fuseki', namespace: NS, prefix: 'fuseki' },
  ]);
});

test('registering adds no prefix', () => {
  expect(Prefix.getFullURI('fuseki')).toBeUndefined();
  expect(Prefix.getFullURI('lincd-fuseki')).toBeUndefined();
});

test('an app map with `fuseki` compacts and expands its terms', () => {
  const map = { fuseki: NS };
  expect(Prefix.toPrefixed(FusekiStore.id, map)).toBe('fuseki:FusekiStore');
  expect(Prefix.toFull('fuseki:FusekiStore', map)).toBe(`${NS}FusekiStore`);
});
