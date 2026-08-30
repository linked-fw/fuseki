import { SparqlDataset } from '@_linked/core/sparql/SparqlDataset';
import type { SparqlJsonResults } from '@_linked/core/sparql/resultMapping';
import fs from 'node:fs/promises';
import path from 'node:path';
import { linkedShape } from '../package.js';
import { fuseki } from '../ontologies/fuseki.js';
import {
  buildAuthHeaders,
  createDataset as createDatasetUtil,
  datasetExists as datasetExistsUtil,
  ensureDatasetExists as ensureDatasetExistsUtil,
} from '../utils/datasets.js';

// Verbose per-query logs are off by default. Enable with DEBUG_FUSEKI=1.
const DEBUG = !!process.env.DEBUG_FUSEKI;

export interface ImportOptions {
  contentType?: string;
  graph?: string;
  mode?: 'append' | 'replace';
}

/**
 * Constructor argument for `new FusekiStore(config)`. Spec aligned with
 * docs/backlog/016-ejection-export-flow.md — a single JSON object passed
 * verbatim from `linked.datasets.json`'s `config` field.
 */
export interface FusekiStoreConfig {
  /** Full SPARQL dataset endpoint, e.g. "http://localhost:3030/myapp-main". */
  endpoint: string;
  /** Optional credentials. Falls back to FUSEKI_USER/FUSEKI_PASSWORD env vars when absent. */
  credentials?: {
    type?: 'basic';
    username: string;
    password: string;
  };
  /** Optional default graph IRI. Falls back to FUSEKI_DEFAULT_GRAPH env var when absent. */
  defaultGraph?: string;
}

/**
 * A Fuseki request that did not succeed.
 *
 * Carries the endpoint, HTTP status and response body, because the useful part of a Fuseki
 * failure is usually its body — a SPARQL parse error names the line and column, and an HTML
 * page means the dataset does not exist.
 */
export class FusekiQueryError extends Error {
  readonly endpoint: string;
  readonly status: number;
  readonly body: string;
  readonly sparql: string;

  constructor(
    message: string,
    details: { endpoint: string; status: number; body: string; sparql: string },
  ) {
    const snippet = details.body.trim().slice(0, 300);
    super(`${message}\n  endpoint: ${details.endpoint}${snippet ? `\n  response: ${snippet}` : ''}`);
    this.name = 'FusekiQueryError';
    this.endpoint = details.endpoint;
    this.status = details.status;
    this.body = details.body;
    this.sparql = details.sparql;
  }
}

@linkedShape
export class FusekiStore extends SparqlDataset {
  static targetClass = fuseki.FusekiStore;
  private baseUrl: string;
  private dataset: string;
  private defaultGraph?: string;
  private credentials?: { username: string; password: string };

  // The union with `string | {id?: string}` (Shape's base constructor signature)
  // keeps the @linkedShape decorator happy. At runtime we only accept the
  // config-object form per docs/backlog/016-ejection-export-flow.md.
  constructor(config?: FusekiStoreConfig | string | { id?: string }) {
    super();
    if (
      !config ||
      typeof config === 'string' ||
      !(config as FusekiStoreConfig).endpoint
    ) {
      throw new Error(
        'FusekiStore: pass a FusekiStoreConfig object with at least { endpoint: "http://host:port/dataset" }.',
      );
    }
    config = config as FusekiStoreConfig;
    const url = new URL(config.endpoint);
    this.baseUrl = `${url.protocol}//${url.host}`;
    this.dataset = FusekiStore.normalizeDatasetName(url.pathname);
    this.defaultGraph =
      config.defaultGraph ?? process.env.FUSEKI_DEFAULT_GRAPH;
    if (config.credentials) {
      this.credentials = {
        username: config.credentials.username,
        password: config.credentials.password,
      };
    }
  }

  private static normalizeDatasetName(
    dataset: string | { value?: string } | { id?: string }
  ): string {
    const raw =
      typeof dataset === 'string'
        ? dataset
        : (dataset as any).value ?? (dataset as any).id ?? dataset.toString();
    return raw
      .trim()
      .replace(/^<(.+)>$/, '$1')
      .replace(/^\/+/, '');
  }

  private getGraphStoreEndpoint(graph?: string): string {
    const base = `${this.baseUrl}/${this.dataset}/data`;
    if (graph) {
      return `${base}?graph=${encodeURIComponent(graph)}`;
    }
    return `${base}?default`;
  }

  private getAdminAuth() {
    if (this.credentials) {
      return {
        username: this.credentials.username,
        password: this.credentials.password,
      };
    }
    const authUser = process.env.FUSEKI_USER;
    const authPass = process.env.FUSEKI_PASSWORD;
    if (!authUser || !authPass) {
      throw new Error(
        'FUSEKI_USER and FUSEKI_PASSWORD env vars are required (or pass `credentials` in FusekiStoreConfig).',
      );
    }
    return { username: authUser, password: authPass };
  }

  private getHeaders(
    extra: Record<string, string> = {}
  ): Record<string, string> {
    const headers = { ...extra };
    if (this.credentials) {
      return buildAuthHeaders(this.credentials, extra);
    }
    const authUser = process.env.FUSEKI_USER;
    const authPass = process.env.FUSEKI_PASSWORD;
    if (authUser && authPass) {
      return buildAuthHeaders(
        { username: authUser, password: authPass },
        extra
      );
    }
    return headers;
  }

  // ── SparqlDataset abstract method implementations ──

  protected async executeSparqlSelect(
    sparql: string
  ): Promise<SparqlJsonResults> {
    const endpoint = `${this.baseUrl}/${this.dataset}/sparql`;
    const headers = this.getHeaders({
      'Content-Type': 'application/sparql-query',
      Accept: 'application/sparql-results+json',
    });

    if (DEBUG) {
      console.log(`[FusekiStore] SPARQL query -> ${endpoint}`);
      console.log(`[FusekiStore] query: ${sparql}`);
    }

    const res = await fetch(endpoint, {
      method: 'POST',
      headers,
      body: sparql,
    });
    const text = await res.text();

    // A failed query must FAIL. This previously returned an empty result set on any
    // non-JSON response, with the comment "so callers don't crash" — which meant a 404, a
    // 500, an HTML error page and a malformed query were all indistinguishable from "no
    // rows". Every read through this store answered "nothing found" when the store was
    // broken, and no caller could tell.
    //
    // That is not a hypothetical: Create Now's existence check reported `false`
    // unconditionally in a running backend, so every re-save took the create branch. Two
    // layers above this were hardened to stop swallowing before anyone looked here.
    if (!res.ok) {
      throw new FusekiQueryError(
        `SPARQL query failed: ${res.status} ${res.statusText}`,
        { endpoint, status: res.status, body: text, sparql },
      );
    }

    try {
      return JSON.parse(text) as SparqlJsonResults;
    } catch {
      throw new FusekiQueryError(
        'SPARQL query returned a 2xx response that is not valid JSON',
        { endpoint, status: res.status, body: text, sparql },
      );
    }
  }

  protected async executeSparqlUpdate(sparql: string): Promise<void> {
    const endpoint = `${this.baseUrl}/${this.dataset}/update`;
    const headers = this.getHeaders({
      'Content-Type': 'application/sparql-update',
      Accept: 'application/sparql-results+json, application/json, text/plain',
    });

    if (DEBUG) {
      console.log(`[FusekiStore] SPARQL update -> ${endpoint}`);
      console.log(`[FusekiStore] query: ${sparql}`);
    }

    const res = await fetch(endpoint, {
      method: 'POST',
      headers,
      body: sparql,
    });

    // Same rule as the query path, and the stakes are higher: a swallowed WRITE reports
    // success for data that was never stored.
    if (!res.ok) {
      const text = await res.text();
      throw new FusekiQueryError(
        `SPARQL update failed: ${res.status} ${res.statusText}`,
        { endpoint, status: res.status, body: text, sparql },
      );
    }
  }

  // ── Fuseki admin operations ──

  async datasetExists(): Promise<boolean> {
    const auth = this.getAdminAuth();
    const result = await datasetExistsUtil({
      baseUrl: this.baseUrl,
      dataset: this.dataset,
      username: auth.username,
      password: auth.password,
    });
    if (result.error) {
      if (result.error === 'Connection refused.') {
        console.error('Connection refused. Is the Fuseki endpoint running?');
      } else {
        console.warn('Fuseki dataset listing failed:', result.error);
      }
    }
    return result.exists;
  }

  async createDataset(options: { dbType?: string } = {}): Promise<any> {
    const auth = this.getAdminAuth();
    const dbType = options.dbType || process.env.FUSEKI_DB_TYPE || 'tdb2';
    const result = await createDatasetUtil({
      baseUrl: this.baseUrl,
      dataset: this.dataset,
      username: auth.username,
      password: auth.password,
      dbType,
    });
    if (!result.ok) {
      console.warn('Fuseki dataset creation failed:', result.error);
    }
    return result;
  }

  async ensureDatasetExists(options: { dbType?: string } = {}): Promise<any> {
    const auth = this.getAdminAuth();
    const dbType = options.dbType || process.env.FUSEKI_DB_TYPE || 'tdb2';
    const result = await ensureDatasetExistsUtil({
      baseUrl: this.baseUrl,
      dataset: this.dataset,
      username: auth.username,
      password: auth.password,
      dbType,
    });
    if (!result.ok) {
      console.warn('Fuseki dataset ensure failed:', result.error);
    }
    return result;
  }

  // ── Graph Store Protocol (data import) ──

  private getContentTypeFromPath(filePath: string): string | null {
    const ext = path.extname(filePath).toLowerCase();
    switch (ext) {
      case '.n3':
        return 'text/n3';
      case '.ttl':
        return 'text/turtle';
      case '.nt':
        return 'application/n-triples';
      case '.nq':
        return 'application/n-quads';
      case '.trig':
        return 'application/trig';
      case '.jsonld':
      case '.json':
        return 'application/ld+json';
      case '.rdf':
      case '.xml':
        return 'application/rdf+xml';
      default:
        return null;
    }
  }

  async importData(
    data: string | Uint8Array,
    options: ImportOptions = {}
  ): Promise<any> {
    const contentType = options.contentType;
    if (!contentType) {
      throw new Error('importData requires a contentType option.');
    }

    const mode = options.mode ?? 'append';
    const targetGraph = options.graph ?? this.defaultGraph;
    const endpoint = this.getGraphStoreEndpoint(targetGraph);
    const method = mode === 'replace' ? 'PUT' : 'POST';

    const headers = this.getHeaders({
      'Content-Type': contentType,
      Accept: 'application/json, text/plain',
    });

    if (DEBUG) {
      console.log(`[FusekiStore] importData (${mode}) -> ${endpoint}`);
    }
    try {
      const res = await fetch(endpoint, {
        method,
        headers,
        body: data as BodyInit,
      });
      const text = await res.text();

      if (!res.ok) {
        console.warn('Fuseki import failed:', text || res.statusText);
        return { error: text || res.statusText, status: res.status };
      }

      if (!text) {
        return { ok: true, status: res.status };
      }

      try {
        return JSON.parse(text);
      } catch {
        return { ok: true, status: res.status, raw: text };
      }
    } catch (err: any) {
      if (err?.cause?.code === 'ECONNREFUSED' || err?.code === 'ECONNREFUSED') {
        console.error(
          'Connection refused. Is the Fuseki endpoint running?',
          err.message
        );
        return { error: 'Connection refused.' };
      }
      console.error('Error when importing data into Fuseki:', err);
      return { error: `Error when importing data into Fuseki: ${err}` };
    }
  }

  async importFile(
    filePath: string,
    options: ImportOptions = {}
  ): Promise<any> {
    const contentType =
      options.contentType || this.getContentTypeFromPath(filePath);
    if (!contentType) {
      throw new Error(`Unsupported file extension for ${filePath}`);
    }

    const data = await fs.readFile(filePath);
    return this.importData(data, { ...options, contentType });
  }
}
