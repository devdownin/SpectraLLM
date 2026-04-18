import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Axios Interceptor for Global Error Handling (Suggestion 3)
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error.response?.data?.message || error.message || 'Spectra API Error';
    console.error(`[API Error] ${message}`, error);
    // Here we could trigger a toast notification system
    return Promise.reject(error);
  }
);

export const statusApi = {
  getStatus: () => api.get('/status'),
};

export const ingestApi = {
  uploadFile: (file: File) => {
    const formData = new FormData();
    formData.append('files', file);
    return api.post('/ingest', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });
  },
  ingestUrls: (urls: string[]) => api.post('/ingest/url', { urls }),
  getTaskStatus: (taskId: string) => api.get(`/ingest/${taskId}`),
  getAllTasks: () => api.get('/ingest'),
  getHistory: () => api.get('/ingest/files'),
};

export const gedApi = {
  listDocuments: (params: any) => api.get('/ged/documents', { params }),
  getDocument: (sha256: string) => api.get(`/ged/documents/${sha256}`),
  deleteDocument: (sha256: string, actor = 'ui') => api.delete(`/ged/documents/${sha256}?actor=${actor}`),
  updateLifecycle: (sha256: string, lifecycle: string, actor = 'ui') =>
    api.put(`/ged/documents/${sha256}/lifecycle?lifecycle=${lifecycle}&actor=${actor}`),
  addTags: (sha256: string, tags: string[], actor = 'ui') =>
    api.post(`/ged/documents/${sha256}/tags?actor=${actor}`, tags),
  removeTags: (sha256: string, tags: string[], actor = 'ui') =>
    api.delete(`/ged/documents/${sha256}/tags?actor=${actor}`, { data: tags }),
  getAuditTrail: (sha256: string) => api.get(`/ged/documents/${sha256}/audit`),
  getStats: () => api.get('/ged/stats'),
  bulkLifecycle: (sha256List: string[], lifecycle: string, actor = 'ui') =>
    api.post(`/ged/documents/bulk/lifecycle?lifecycle=${lifecycle}&actor=${actor}`, sha256List),
  bulkAddTags: (sha256List: string[], tags: string[], actor = 'ui') =>
    api.post(`/ged/documents/bulk/tags?actor=${actor}`, { sha256List, tags }),
};

export const documentsApi = {
  listIngestedFiles: () => api.get('/ingest/files'),
};

export const datasetApi = {
  getStats: () => api.get('/dataset/stats'),
  generateDataset: (maxChunks = 0) => api.post(`/dataset/generate?maxChunks=${maxChunks}`),
  getGenerationStatus: (taskId: string) => api.get(`/dataset/generate/${taskId}`),
  getAllTasks: () => api.get('/dataset/generate'),
};

export const fineTuningApi = {
  getJobs: () => api.get('/fine-tuning'),
  getJob: (jobId: string) => api.get(`/fine-tuning/${jobId}`),
  createJob: (job: any) => api.post('/fine-tuning', job),
  getModels: () => api.get('/fine-tuning/models'),
};

export const evaluationApi = {
  getAll: () => api.get('/evaluation'),
  get: (evalId: string) => api.get(`/evaluation/${evalId}`),
  submit: (request?: { modelName?: string; testSetSize?: number; jobId?: string }) =>
    api.post('/evaluation', request ?? {}),
};

export const recipeApi = {
  list: () => api.get('/fine-tuning/recipes'),
  get: (name: string) => api.get(`/fine-tuning/recipes/${name}`),
  export: (request: Record<string, unknown>) =>
    api.post('/fine-tuning/recipe/export', request, { responseType: 'blob' }),
};

export const dpoApi = {
  generate: (maxPairs = 0) => api.post(`/dataset/dpo/generate?maxPairs=${maxPairs}`),
  getTask: (taskId: string) => api.get(`/dataset/dpo/generate/${taskId}`),
  getAllTasks: () => api.get('/dataset/dpo/generate'),
  getStats: () => api.get('/dataset/dpo/stats'),
};

export interface StreamEvent {
  type: 'sources' | 'token' | 'done' | 'error';
  data: string;
}

export const queryApi = {
  query: (question: string, model?: string, useRag = true) =>
    api.post('/query', { question, model, useRag }),

  /**
   * Streaming RAG query via POST SSE (EventSource ne supporte pas POST).
   * Yields StreamEvent objects: sources → token* → done | error.
   */
  async *queryStream(
    question: string,
    useRag = true,
    signal?: AbortSignal,
    topCandidates?: number,
  ): AsyncGenerator<StreamEvent> {
    const body: Record<string, unknown> = { question, useRag };
    if (topCandidates !== undefined) body.topCandidates = topCandidates;
    const response = await fetch('/api/query/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal,
    });

    if (!response.ok || !response.body) {
      throw new Error(`HTTP ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let pendingEvent = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() ?? '';

      for (const line of lines) {
        if (line.startsWith('event: ')) {
          pendingEvent = line.slice(7).trim();
        } else if (line.startsWith('data: ') && pendingEvent) {
          yield { type: pendingEvent as StreamEvent['type'], data: line.slice(6) };
          pendingEvent = '';
        }
      }
    }
  },
};

export const configApi = {
  getModelConfig: () => api.get('/config/model'),
  setModelConfig: (config: any) => api.post('/config/model', config),
};

export const modelsHubApi = {
  getRecommendations: (params: { limit?: number; memory?: string; ram?: string; cpuCores?: number } = {}) =>
    api.get('/models/hub/recommendations', { params }),
  installModel: (modelName: string, quant?: string, autoActivate = false) =>
    api.post(`/models/hub/install?modelName=${encodeURIComponent(modelName)}${quant ? `&quant=${quant}` : ''}&autoActivate=${autoActivate}`),
  getProgressSource: (modelName: string) =>
    new EventSource(`/api/models/hub/install/${encodeURIComponent(modelName)}/progress`),
};

export default api;
