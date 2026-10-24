/**
 * `FusekiStore`'s graph targets (`ontologyGraph`, `shapeGraph`): taken from the store config,
 * filled from the environment the way a host does it (`graphTargetOptionsFromEnv` +
 * `setGraphTargets(..., {onlyUnset: true})`, which `@_linked/server` and `loadStores` run), and
 * checked by core. Precedence: the config value, then the environment, then `'package'`.
 */
import { graphTargetOptionsFromEnv } from '@_linked/core/utils/graphTargets';
import { loadStores } from '@_linked/core/utils/loadStores';
import { FusekiStore, type FusekiStoreConfig } from './FusekiStore.js';

const ENDPOINT = 'http://localhost:3177/some-dataset';
const SHARED_ONTOLOGIES = 'https://example.org/graphs/ontologies';
const SHARED_SHAPES = 'https://example.org/graphs/shapes';

const store = (extra: Partial<FusekiStoreConfig> = {}) => new FusekiStore({ endpoint: ENDPOINT, ...extra });

/** What a host does with its environment: fill the targets the store was not given. */
function fillFromEnv(s: FusekiStore, env: Record<string, string | undefined>): FusekiStore {
  s.setGraphTargets(graphTargetOptionsFromEnv(env), { onlyUnset: true });
  return s;
}

describe('FusekiStore graph targets', () => {
  it("defaults to 'package' for both", () => {
    expect(store().graphTargets()).toEqual({ ontologyGraph: 'package', shapeGraph: 'package' });
  });

  it('takes both from the config', () => {
    expect(store({ ontologyGraph: 'default', shapeGraph: SHARED_SHAPES }).graphTargets()).toEqual({
      ontologyGraph: 'default',
      shapeGraph: SHARED_SHAPES,
    });
  });

  it('takes both from the environment when the config sets neither', () => {
    const s = fillFromEnv(store(), {
      LINKED_ONTOLOGY_GRAPH: SHARED_ONTOLOGIES,
      LINKED_SHAPE_GRAPH: 'default',
    });
    expect(s.graphTargets()).toEqual({ ontologyGraph: SHARED_ONTOLOGIES, shapeGraph: 'default' });
  });

  it('keeps the config value when the environment sets the same setting', () => {
    const s = fillFromEnv(store({ ontologyGraph: 'package', shapeGraph: SHARED_SHAPES }), {
      LINKED_ONTOLOGY_GRAPH: 'default',
      LINKED_SHAPE_GRAPH: 'default',
    });
    expect(s.graphTargets()).toEqual({ ontologyGraph: 'package', shapeGraph: SHARED_SHAPES });
  });

  it('fills only the setting the config leaves unset', () => {
    const s = fillFromEnv(store({ ontologyGraph: 'default' }), {
      LINKED_ONTOLOGY_GRAPH: SHARED_ONTOLOGIES,
      LINKED_SHAPE_GRAPH: SHARED_SHAPES,
    });
    expect(s.graphTargets()).toEqual({ ontologyGraph: 'default', shapeGraph: SHARED_SHAPES });
  });

  it('refuses an environment value that names the config’s shared graph for the other setting', () => {
    expect(() =>
      fillFromEnv(store({ ontologyGraph: SHARED_ONTOLOGIES }), { LINKED_SHAPE_GRAPH: SHARED_ONTOLOGIES }),
    ).toThrow(/both name/);
  });

  it('treats an empty config value as unset', () => {
    const s = fillFromEnv(store({ ontologyGraph: '' }), { LINKED_ONTOLOGY_GRAPH: 'default' });
    expect(s.graphTargets().ontologyGraph).toBe('default');
  });

  it.each([
    ['ontologyGraph', 'not an iri'],
    ['shapeGraph', 'urn:x-arq:DefaultGraph'],
  ] as const)('refuses an invalid %s (%s) when the store is built', (setting, value) => {
    expect(() => store({ [setting]: value })).toThrow(new RegExp(`Invalid ${setting}`));
  });

  it('refuses one shared graph for both settings', () => {
    expect(() => store({ ontologyGraph: SHARED_SHAPES, shapeGraph: SHARED_SHAPES })).toThrow(
      /both name/,
    );
  });

  it('still refuses a config without an endpoint', () => {
    expect(() => new FusekiStore({ ontologyGraph: 'default' } as FusekiStoreConfig)).toThrow(
      /FusekiStoreConfig object/,
    );
  });
});

describe('FusekiStore graph targets through loadStores', () => {
  // `loadStores` imports the entry's `store` specifier; core's import hook points it at this
  // source, not the built copy in node_modules.
  const IMPORT_MODULE = Symbol.for('@_linked/core:importModule');
  const STORE = '@_linked/fuseki/shapes/FusekiStore';
  beforeAll(() => {
    (globalThis as Record<symbol, unknown>)[IMPORT_MODULE] = async (specifier: string) =>
      specifier === STORE ? { FusekiStore } : import(specifier);
  });
  afterAll(() => {
    delete (globalThis as Record<symbol, unknown>)[IMPORT_MODULE];
  });

  const datasets = (config: Record<string, unknown>) => ({
    datasets: { main: { store: STORE, config: { endpoint: ENDPOINT, ...config } } },
  });

  it('takes the entry config value', async () => {
    const { main } = await loadStores<FusekiStore>(datasets({ shapeGraph: SHARED_SHAPES }));
    expect(main).toBeInstanceOf(FusekiStore);
    expect(main.graphTargets()).toEqual({ ontologyGraph: 'package', shapeGraph: SHARED_SHAPES });
  });

  it('fills unset settings from the environment, and the entry config wins', async () => {
    const graphTargets = graphTargetOptionsFromEnv({
      LINKED_ONTOLOGY_GRAPH: SHARED_ONTOLOGIES,
      LINKED_SHAPE_GRAPH: 'default',
    });
    const { main } = await loadStores<FusekiStore>(datasets({ shapeGraph: SHARED_SHAPES }), { graphTargets });
    expect(main.graphTargets()).toEqual({ ontologyGraph: SHARED_ONTOLOGIES, shapeGraph: SHARED_SHAPES });
  });
});
