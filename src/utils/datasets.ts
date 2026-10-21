export type FusekiAuthConfig = {
  username: string;
  password: string;
};

export type FusekiDatasetConfig = FusekiAuthConfig & {
  baseUrl: string;
  dataset: string;
};

export type FusekiCreateDatasetConfig = FusekiDatasetConfig & {
  dbType: string;
};

export type DatasetAdminResult = {
  ok: boolean;
  status?: number;
  json?: any;
  raw?: string;
  error?: string;
};

export type DatasetExistsResult = {
  exists: boolean;
  error?: string;
};

export type DatasetClearResult = {
  ok: boolean;
  status?: number;
  error?: string;
  raw?: string;
};

export const buildAuthHeaders = (
  auth: FusekiAuthConfig,
  extra: Record<string, string> = {}
): Record<string, string> => {
  return {
    ...extra,
    Authorization:
      'Basic ' +
      Buffer.from(`${auth.username}:${auth.password}`).toString('base64'),
  };
};

const getAdminDatasetsEndpoint = (baseUrl: string): string =>
  `${baseUrl}/$/datasets`;

export async function datasetExists(
  config: FusekiDatasetConfig
): Promise<DatasetExistsResult> {
  const endpoint = getAdminDatasetsEndpoint(config.baseUrl);
  const headers = buildAuthHeaders(config, {
    Accept: 'application/json, text/plain',
  });

  try {
    const res = await fetch(endpoint, { method: 'GET', headers });
    const text = await res.text();
    if (!res.ok) {
      return { exists: false, error: text || res.statusText };
    }
    if (!text) {
      return { exists: false };
    }
    let json: any;
    try {
      json = JSON.parse(text);
    } catch {
      return {
        exists: false,
        error: 'Fuseki dataset listing returned invalid JSON.',
      };
    }

    const expectedName = '/' + config.dataset;
    const datasets = Array.isArray(json?.datasets) ? json.datasets : [];

    const exists = datasets.some((entry: any) => {
      const name = entry?.['ds.name'];
      return name && typeof name === 'string' && expectedName === name;
    });

    return { exists };
  } catch (err: any) {
    if (err?.cause?.code === 'ECONNREFUSED' || err?.code === 'ECONNREFUSED') {
      return { exists: false, error: 'Connection refused.' };
    }
    return {
      exists: false,
      error: `Error when checking if Fuseki dataset ${config.dataset} exists. Fetch ${endpoint}: ${err}`,
    };
  }
}

export async function createDataset(
  config: FusekiCreateDatasetConfig
): Promise<DatasetAdminResult> {
  const endpoint = getAdminDatasetsEndpoint(config.baseUrl);
  const headers = buildAuthHeaders(config, {
    'Content-Type': 'application/x-www-form-urlencoded; charset=utf-8',
    Accept: 'application/json, text/plain',
  });

  const body = new URLSearchParams({
    dbName: config.dataset,
    dbType: config.dbType,
  }).toString();

  try {
    const res = await fetch(endpoint, { method: 'POST', headers, body });
    const text = await res.text();

    if (res.ok || res.status === 409) {
      if (!text) {
        return { ok: true, status: res.status };
      }
      try {
        return { ok: true, status: res.status, json: JSON.parse(text) };
      } catch {
        return { ok: true, status: res.status, raw: text };
      }
    }

    return { ok: false, status: res.status, error: text || res.statusText };
  } catch (err: any) {
    if (err?.cause?.code === 'ECONNREFUSED' || err?.code === 'ECONNREFUSED') {
      return { ok: false, error: 'Connection refused.' };
    }
    return {
      ok: false,
      error: `Error when creating dataset in Fuseki: ${err}`,
    };
  }
}

export async function ensureDatasetExists(
  config: FusekiCreateDatasetConfig
): Promise<DatasetAdminResult & { created?: boolean }> {
  const exists = await datasetExists(config);
  if (exists.exists) {
    return { ok: true, created: false };
  }
  if (exists.error) {
    return { ok: false, error: exists.error };
  }

  const createResult = await createDataset(config);
  if (createResult?.ok) {
    return { ...createResult, created: true };
  }
  return createResult;
}

export async function clearDataset(
  config: FusekiDatasetConfig
): Promise<DatasetClearResult> {
  const endpoint = `${config.baseUrl}/${config.dataset}/update`;
  const headers = buildAuthHeaders(config, {
    'Content-Type': 'application/sparql-update',
  });

  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers,
      body: 'CLEAR ALL',
    });
    const text = await res.text();
    if (!res.ok) {
      return { ok: false, status: res.status, error: text || res.statusText };
    }
    return { ok: true, status: res.status, raw: text };
  } catch (err: any) {
    if (err?.cause?.code === 'ECONNREFUSED' || err?.code === 'ECONNREFUSED') {
      return { ok: false, error: 'Connection refused.' };
    }
    return { ok: false, error: `Error when clearing dataset: ${err}` };
  }
}

export type DatasetsBatchConfig = {
  baseUrl: string;
  username: string;
  password: string;
  dbType?: string;
  log?: (message: string) => void;
};

export async function ensureDatasetsExist(
  datasets: string[],
  config: DatasetsBatchConfig
): Promise<void> {
  if (!config.dbType) {
    throw new Error('dbType is required to create datasets.');
  }

  for (const dataset of datasets) {
    const result = await ensureDatasetExists({
      baseUrl: config.baseUrl,
      dataset,
      username: config.username,
      password: config.password,
      dbType: config.dbType,
    });
    if (result?.created) {
      config.log?.(`Created dataset ${dataset} (empty)`);
      continue;
    }
    if (result?.ok) {
      continue;
    }
    const detail = result?.error ? String(result.error) : 'Unknown error';
    throw new Error(`Failed to ensure dataset ${dataset}: ${detail}`);
  }
}

export async function clearDatasets(
  datasets: string[],
  config: Omit<DatasetsBatchConfig, 'dbType'>
): Promise<void> {
  for (const dataset of datasets) {
    const result = await clearDataset({
      baseUrl: config.baseUrl,
      dataset,
      username: config.username,
      password: config.password,
    });
    if (result?.ok) {
      config.log?.(`Cleared dataset ${dataset}`);
      continue;
    }
    const detail = result?.error ? String(result.error) : 'Unknown error';
    throw new Error(`Failed to clear dataset ${dataset}: ${detail}`);
  }
}

/**
 * The stored-only endpoint of a dataset on the linked Fuseki image: `${base}/${dataset}/stored`
 * (trailing slashes of `base` are trimmed). It reads the stored triples only, never the ones the
 * dataset infers:
 * - `GET` with `Accept: application/n-quads`: the whole stored dataset, every graph (backups,
 *   copies);
 * - `GET ?default` / `GET ?graph=<iri>`: one graph (Graph Store Protocol read);
 * - `GET ?query=…`, a `POST` with `Content-Type: application/sparql-query`, or a form `query=`:
 *   a SPARQL query over the stored triples (counts, fork copies).
 * Writes answer 405. Only datasets that declare the `stored` endpoint have it (every dataset
 * created through the image's admin form does); elsewhere the URL answers 404.
 */
export function storedEndpoint(base: string, dataset: string): string {
  return `${base.replace(/\/+$/, '')}/${dataset}/stored`;
}
