/**
 * `FusekiStore`'s graph settings: Jena's names for the portable default and union graphs, the
 * store-level `isolateLinkedTypeChecks` option, one graph-registry cache key per Fuseki dataset,
 * and graph writes to store-native or portable names refused before any request.
 */
import { jest } from '@jest/globals';
import { graphs } from '@_linked/core/ontologies/graphs';
import { FusekiStore, jenaGraphName } from './FusekiStore.js';

const ONTOLOGY_GRAPH = 'https://linked.cm/ont/core/OntologyGraph';
const store = (endpoint = 'http://localhost:3177/some-dataset') => new FusekiStore({ endpoint });
const key = (s: FusekiStore): string => (s as any).graphRegistryKey();

const ORIGINAL_FETCH = global.fetch;
afterEach(() => {
  global.fetch = ORIGINAL_FETCH;
});

describe('jenaGraphName', () => {
  it('maps the portable default and union graphs to Jena’s names', () => {
    expect(jenaGraphName(graphs.default)).toBe('urn:x-arq:DefaultGraph');
    expect(jenaGraphName(graphs.union)).toBe('urn:x-arq:UnionGraph');
  });

  it('keeps every other graph IRI', () => {
    const g = 'https://linked.cm/pkg/fuseki/graph/ontology';
    expect(jenaGraphName(g)).toBe(g);
    expect(jenaGraphName(graphs.registry)).toBe(graphs.registry);
  });
});

describe('store options', () => {
  it('turns on isolateLinkedTypeChecks and maps graph names with jenaGraphName', () => {
    const options = (store() as any).options;
    expect(options.isolateLinkedTypeChecks).toBe(true);
    expect(options.mapGraphName).toBe(jenaGraphName);
  });
});

describe('graphRegistryKey', () => {
  it('is the dataset endpoint, shared by every store for that dataset', () => {
    expect(key(store())).toBe('http://localhost:3177/some-dataset');
    expect(key(store('http://localhost:3177/some-dataset/'))).toBe(key(store()));
    expect(key(store('http://localhost:3177/other-dataset'))).not.toBe(key(store()));
    expect(key(store('http://localhost:3030/some-dataset'))).not.toBe(key(store()));
  });
});

describe('graph writes to reserved names', () => {
  const content = { mediaType: 'application/n-triples' as const, text: '<a:s> <a:p> <a:o> .\n' };

  it.each([
    ['urn:x-arq:DefaultGraph'],
    ['urn:x-arq:UnionGraph'],
    [graphs.default],
    [graphs.union],
    [graphs.registry],
  ])('refuses a write to <%s> without sending a request', async (graph) => {
    const fetchSpy = jest.fn();
    global.fetch = fetchSpy as any;
    await expect(
      store().graphQuery({ kind: 'graph_replace', graph, recordClass: ONTOLOGY_GRAPH, content }),
    ).rejects.toMatchObject({ name: 'GraphQueryError', code: 'graph-name-read-only' });
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
