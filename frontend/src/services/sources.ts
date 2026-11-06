import api from './api';
import type { ConnectionResult, DocumentPage, Source, SourceCatalogEntry, SourceRequest, SourceRun, SourceDocument } from '../types/sources';
const path = (id: string) => `/sources/${encodeURIComponent(id)}`;
export const sourcesApi = {
  list: () => api.get<Source[]>('/sources'),
  catalog: () => api.get<SourceCatalogEntry[]>('/sources/catalog'),
  create: (request: SourceRequest) => api.post<Source>('/sources', request),
  update: (id: string, request: SourceRequest) => api.put<Source>(path(id), request),
  remove: (id: string) => api.delete(path(id)),
  test: (request: SourceRequest) => api.post<ConnectionResult>('/sources/test', request),
  testSaved: (id: string) => api.post<ConnectionResult>(`${path(id)}/test`),
  sync: (id: string) => api.post<SourceRun>(`${path(id)}/sync`),
  cancel: (id: string) => api.post(`${path(id)}/cancel`),
  runs: (id: string) => api.get<SourceRun[]>(`${path(id)}/runs`),
  preview: (id: string, documentId: string) => api.get<{ chunks: string[]; truncated: boolean }>(`${path(id)}/documents/${encodeURIComponent(documentId)}/preview`),
  document: (id: string, documentId: string) => api.get<SourceDocument>(`${path(id)}/documents/${encodeURIComponent(documentId)}`),
  documents: (id: string, page = 0) => api.get<DocumentPage>(`${path(id)}/documents`, { params: { page, size: 25 } }),
};
