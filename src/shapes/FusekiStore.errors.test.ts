/**
 * A failed Fuseki request must FAIL.
 *
 * Both methods used to swallow. `executeSparqlSelect` returned an empty result set on any
 * non-JSON response ("so callers don't crash"), and `executeSparqlUpdate` only warned on a
 * non-2xx. So a 404, a 500, an HTML error page and a malformed query were all indistinguishable
 * from "no rows" — and a write that never landed reported success.
 *
 * The cost was real: Create Now's existence check reported `false` unconditionally in a running
 * backend, so every re-save took the `create` branch instead of `update`. Two layers above this
 * were hardened against swallowing before anyone looked here, which is the argument for these
 * tests — the behaviour is invisible until something downstream is quietly wrong.
 */
import { jest } from '@jest/globals';
import { FusekiStore, FusekiQueryError } from './FusekiStore.js';

const store = () => new FusekiStore({ endpoint: 'http://localhost:3030/some-dataset' });
/** Reach the protected methods without a subclass — the point is the wire behaviour. */
const select = (s: FusekiStore, q = 'SELECT * WHERE { ?s ?p ?o }') => (s as any).executeSparqlSelect(q);
const update = (s: FusekiStore, q = 'INSERT DATA { <a:s> <a:p> <a:o> }') => (s as any).executeSparqlUpdate(q);

const respond = (body: string, init: { status?: number; statusText?: string } = {}) => {
  const { status = 200, statusText = 'OK' } = init;
  global.fetch = jest.fn(async () => ({
    ok: status >= 200 && status < 300, status, statusText, text: async () => body,
  })) as any;
};

const ORIGINAL_FETCH = global.fetch;
afterEach(() => { global.fetch = ORIGINAL_FETCH; });

describe('executeSparqlSelect', () => {
  it('parses a valid result set', async () => {
    respond(JSON.stringify({ head: { vars: ['s'] }, results: { bindings: [{ s: { value: 'a:s' } }] } }));
    await expect(select(store())).resolves.toMatchObject({ results: { bindings: [{ s: { value: 'a:s' } }] } });
  });

  it('rejects on a non-2xx instead of reporting no rows', async () => {
    respond('Error 404: Dataset not found', { status: 404, statusText: 'Not Found' });
    await expect(select(store())).rejects.toThrow(FusekiQueryError);
  });

  it('rejects on a 2xx that is not JSON — the SPARQL parse-error case', async () => {
    // Fuseki answers a malformed query with a 200 and a text body. This is the exact shape that
    // used to become an empty result set.
    respond('Parse error:\nline 8, column 3: Unresolved prefixed name');
    await expect(select(store())).rejects.toThrow(/not valid JSON/);
  });

  it('carries the response body, because that is where the diagnosis is', async () => {
    respond('Parse error: line 8, column 3', { status: 400, statusText: 'Bad Request' });
    const error = await select(store()).catch((e: FusekiQueryError) => e);
    expect(error).toBeInstanceOf(FusekiQueryError);
    expect(error.status).toBe(400);
    expect(error.body).toContain('line 8, column 3');
    expect(error.message).toContain('line 8, column 3');
    expect(error.endpoint).toContain('/some-dataset/sparql');
  });

  it('NEVER resolves to an empty result set on failure (the original bug)', async () => {
    respond('<html>Service Unavailable</html>', { status: 503, statusText: 'Service Unavailable' });
    const outcome = await select(store()).then(
      (value: unknown) => ({ resolved: value }), (error: Error) => ({ error }));
    expect(outcome).not.toHaveProperty('resolved');
  });
});

describe('executeSparqlUpdate', () => {
  it('resolves on success', async () => {
    respond('', { status: 204, statusText: 'No Content' });
    await expect(update(store())).resolves.toBeUndefined();
  });

  it('rejects on a failed write rather than warning — a swallowed write reports success for data that was never stored', async () => {
    respond('Update parse error', { status: 400, statusText: 'Bad Request' });
    await expect(update(store())).rejects.toThrow(FusekiQueryError);
  });
});
