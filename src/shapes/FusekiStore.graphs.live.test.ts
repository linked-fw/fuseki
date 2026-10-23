/**
 * Reads from graphs through `FusekiStore`, live, on an image that builds its hierarchy from the
 * graph registry (`ghcr.io/linked-fw/fuseki:6.2.0-2` or later).
 *
 * Skipped unless `FUSEKI_LIVE_URL` (e.g. `http://localhost:3177`) and `FUSEKI_LIVE_ADMIN_PASSWORD`
 * are set. Creates its own dataset and deletes it afterwards.
 *
 * Data: the default graph holds `default-thing` (a Thing) and `sub-in-default` (a Sub); a package
 * ontology graph, registered through the store, holds `Sub rdfs:subClassOf Thing` and
 * `graph-thing` (a Thing). So `sub-in-default` reads as a Thing only through the registered graph's
 * hierarchy.
 */
import { linkedShape } from '../package.js';
import { Shape } from '@_linked/core/shapes/Shape';
import { literalProperty } from '@_linked/core/shapes/SHACL';
import { OntologyGraph } from '@_linked/core/shapes/NamedGraph';
import { graphs } from '@_linked/core/ontologies/graphs';
import { FusekiStore } from './FusekiStore.js';
import { createDataset, buildAuthHeaders } from '../utils/datasets.js';

const BASE_URL = process.env.FUSEKI_LIVE_URL?.replace(/\/+$/, '');
const ADMIN_PASSWORD = process.env.FUSEKI_LIVE_ADMIN_PASSWORD;
const live = BASE_URL && ADMIN_PASSWORD ? describe : describe.skip;
if (!BASE_URL || !ADMIN_PASSWORD) {
  console.log('[fuseki live] skipped: set FUSEKI_LIVE_URL and FUSEKI_LIVE_ADMIN_PASSWORD to run it');
}

const E = 'https://example.org/fuseki-live/';
const THING = `${E}Thing`;
const SUB = `${E}Sub`;
const NAME = `${E}name`;
const RDF_TYPE = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';
const SUBCLASS_OF = 'http://www.w3.org/2000/01/rdf-schema#subClassOf';
const PACKAGE = '@_linked/fuseki-live-test';
const GRAPH = OntologyGraph.iriFor(PACKAGE);

@linkedShape
class LiveThing extends Shape {
  static targetClass = { id: THING };

  @literalProperty({ path: { id: NAME }, maxCount: 1 })
  get name(): string {
    return '';
  }
}

const thing = (id: string, cls: string, name: string) =>
  `<${E}${id}> <${RDF_TYPE}> <${cls}> .\n<${E}${id}> <${NAME}> "${name}" .\n`;

live('FusekiStore reads from graphs (live)', () => {
  const dataset = `fuseki-live-graphs-${Date.now()}`;
  const admin = { username: 'admin', password: ADMIN_PASSWORD ?? '' };
  let store: FusekiStore;

  const names = async (from: string): Promise<string[]> => {
    const rows = await LiveThing.select((t) => t.name).from(from).exec(store);
    return rows.map((r: { name: string }) => r.name).sort();
  };

  beforeAll(async () => {
    const created = await createDataset({ baseUrl: BASE_URL!, dataset, dbType: 'tdb2', ...admin });
    if (!created.ok) throw new Error(`could not create ${dataset}: ${created.error}`);
    store = new FusekiStore({ endpoint: `${BASE_URL}/${dataset}`, credentials: admin });
    await (store as any).executeSparqlUpdate(
      `INSERT DATA { ${thing('default-thing', THING, 'in the default graph')} ${thing('sub-in-default', SUB, 'a Sub in the default graph')} }`,
    );
    await OntologyGraph.create({ id: GRAPH, package: PACKAGE, version: '1.0.0' })
      .withContent({
        mediaType: 'application/n-triples',
        text: `<${SUB}> <${SUBCLASS_OF}> <${THING}> .\n${thing('graph-thing', THING, 'in the package graph')}`,
      })
      .exec(store);
  });

  afterAll(async () => {
    await fetch(`${BASE_URL}/$/datasets/${dataset}`, {
      method: 'DELETE',
      headers: buildAuthHeaders(admin, {}),
    });
  });

  it('.from(graphs.union) returns a named-graph triple, not the default graph', async () => {
    expect(await names(graphs.union)).toEqual(['in the package graph']);
  });

  it('.from(graphs.default) alone reads the default graph, inferring through the registered graph', async () => {
    expect(await names(graphs.default)).toEqual(['a Sub in the default graph', 'in the default graph']);
  });

  it('.from(OntologyGraph.iriFor(pkg)) reads that package graph only', async () => {
    expect(GRAPH).toBe('https://linked.cm/pkg/fuseki-live-test/graph/ontology');
    expect(await names(GRAPH)).toEqual(['in the package graph']);
  });

  it('.from(graphs.allOntologies) lists the registered graph', async () => {
    expect(await names(graphs.allOntologies)).toEqual(['in the package graph']);
  });

  it('a second store for the same dataset shares the registry cache key', async () => {
    const other = new FusekiStore({ endpoint: `${BASE_URL}/${dataset}`, credentials: admin });
    expect((other as any).graphRegistryKey()).toBe((store as any).graphRegistryKey());
    const rows = await LiveThing.select((t) => t.name).from(graphs.allOntologies).exec(other);
    expect(rows.map((r: { name: string }) => r.name)).toEqual(['in the package graph']);
  });
});
