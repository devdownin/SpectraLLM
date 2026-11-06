import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError } from 'axios';
import type { AxiosAdapter, InternalAxiosRequestConfig } from 'axios';
import api from './api';
import { sourcesApi } from './sources';
import type { SourceRequest } from '../types/sources';
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));
const originalAdapter = api.defaults.adapter;
let requests: InternalAxiosRequestConfig[];
const request: SourceRequest = {
  name: 'Product documentation', type: 'S3', collection: 'spectra_documents', enabled: true, intervalMinutes: 60,
  config: { bucket: 'docs', prefix: 'product/', region: 'us-east-1', accessKeyEnv: 'SPECTRA_S3_ACCESS_KEY', secretKeyEnv: 'SPECTRA_S3_SECRET_KEY', extensions: ['pdf'], excludePatterns: [], maxDocuments: 1000, maxDepth: 2, maxFileSizeMb: 25 },
};
beforeEach(() => {
  requests = [];
  api.defaults.adapter = async config => {
    requests.push(config);
    return { config, data: {}, status: 200, statusText: 'OK', headers: {} };
  };
});
afterEach(() => { api.defaults.adapter = originalAdapter; vi.restoreAllMocks(); });
const contracts = () => requests.map(config => `${config.method?.toUpperCase()} ${api.getUri(config)}`);
describe('Source HTTP lifecycle contract', () => {
  it('uses read-only discovery, explicit test and sync actions, and a body-preserving settings update', async () => {
    await sourcesApi.catalog(); await sourcesApi.list(); await sourcesApi.test(request); await sourcesApi.create(request);
    await sourcesApi.testSaved('source-1'); await sourcesApi.sync('source-1'); await sourcesApi.runs('source-1');
    await sourcesApi.documents('source-1'); await sourcesApi.documents('source-1', 3);
    await sourcesApi.document('source-1', 'document-1'); await sourcesApi.preview('source-1', 'document-1');
    await sourcesApi.cancel('source-1'); await sourcesApi.update('source-1', { ...request, enabled: false }); await sourcesApi.remove('source-1');
    expect(contracts()).toEqual([
      'GET /api/sources/catalog', 'GET /api/sources', 'POST /api/sources/test', 'POST /api/sources',
      'POST /api/sources/source-1/test', 'POST /api/sources/source-1/sync', 'GET /api/sources/source-1/runs',
      'GET /api/sources/source-1/documents?page=0&size=25', 'GET /api/sources/source-1/documents?page=3&size=25',
      'GET /api/sources/source-1/documents/document-1', 'GET /api/sources/source-1/documents/document-1/preview',
      'POST /api/sources/source-1/cancel', 'PUT /api/sources/source-1', 'DELETE /api/sources/source-1',
    ]);
    expect(JSON.parse(requests[2].data)).toEqual(request);
    expect(JSON.parse(requests[3].data)).toEqual(request);
    expect(JSON.parse(requests[12].data)).toEqual({ ...request, enabled: false });
    expect(requests[3].headers.get('Content-Type')).toBe('application/json');
  });

  it('keeps source and document identities within their path segments', async () => {
    const sourceId = 'source/../?tenant=other'; const documentId = 'doc/#?download=1';
    await sourcesApi.preview(sourceId, documentId); await sourcesApi.document(sourceId, documentId);
    await sourcesApi.update(sourceId, request); await sourcesApi.remove(sourceId);
    expect(contracts()).toEqual([
      'GET /api/sources/source%2F..%2F%3Ftenant%3Dother/documents/doc%2F%23%3Fdownload%3D1/preview',
      'GET /api/sources/source%2F..%2F%3Ftenant%3Dother/documents/doc%2F%23%3Fdownload%3D1',
      'PUT /api/sources/source%2F..%2F%3Ftenant%3Dother',
      'DELETE /api/sources/source%2F..%2F%3Ftenant%3Dother',
    ]);
  });

  it('propagates a refused read so the UI can distinguish access denial from an empty source', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const adapter: AxiosAdapter = async config => {
      throw new AxiosError('Access denied', 'ERR_BAD_REQUEST', config, undefined, { config, data: { detail: 'No access to source' }, status: 403, statusText: 'Forbidden', headers: {} });
    };
    api.defaults.adapter = adapter;
    await expect(sourcesApi.documents('private')).rejects.toMatchObject({ response: { status: 403, data: { detail: 'No access to source' } } });
  });
});
