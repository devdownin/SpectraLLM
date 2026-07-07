import axios from 'axios';
import { toast } from 'sonner';

const api = axios.create({
  baseURL: '/api',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Gestion globale des erreurs : signale les pannes réseau et les erreurs serveur
// (5xx) que les pages ne gèrent généralement pas. Les 4xx restent traitées
// localement. Les toasts utilisent un id stable pour ne pas s'empiler en cas de
// polling répété pendant une panne.
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status;
    const message = error.response?.data?.message || error.message || 'Spectra API Error';
    console.error(`[API Error] ${message}`, error);
    if (!error.response) {
      toast.error('Connection failed', {
        id: 'api-network-error',
        description: 'The Spectra service is unreachable.',
      });
    } else if (status >= 500) {
      toast.error('Server error', { id: 'api-server-error', description: message });
    }
    return Promise.reject(error);
  }
);

export const statusApi = {
  getStatus: () => api.get('/status'),
};

export const healthApi = {
  getServices: () => api.get('/health/services'),
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
  getHistory: (params?: { page?: number; size?: number; q?: string }) =>
    api.get('/ingest/files', { params }),
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
  submitBatch: (modelNames: string[], testSetSize?: number) =>
    api.post('/evaluation/batch', { modelNames, testSetSize }),
  compare: (evalIds: string[], baseline?: string) =>
    api.get('/evaluation/compare', {
      params: { evalIds: evalIds.join(','), ...(baseline ? { baseline } : {}) },
    }),
  submitAb: (modelA: string, modelB: string, testSetSize?: number) =>
    api.post('/evaluation/ab', { modelA, modelB, testSetSize }),
  getAllAb: () => api.get('/evaluation/ab'),
  getAb: (abId: string) => api.get(`/evaluation/ab/${abId}`),
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

export const commentApi = {
  list: (sha256: string) =>
    api.get(`/ged/documents/${sha256}/comments`),
  addHuman: (sha256: string, content: string, actor = 'ui') =>
    api.post(`/ged/documents/${sha256}/comments?actor=${actor}`, { content, generate: false }),
  generate: (sha256: string, focus: string, actor = 'ui') =>
    api.post(`/ged/documents/${sha256}/comments?actor=${actor}`, { content: focus, generate: true }),
  rate: (sha256: string, id: number, rating: 'APPROVED' | 'REJECTED' | 'NONE', actor = 'ui') =>
    api.patch(`/ged/documents/${sha256}/comments/${id}/rating?rating=${rating}&actor=${actor}`),
  delete: (sha256: string, id: number) =>
    api.delete(`/ged/documents/${sha256}/comments/${id}`),
  exportDpo: () =>
    api.post('/ged/documents/export/comments-dpo'),
};

export interface StreamEvent {
  type: 'sources' | 'token' | 'done' | 'error';
  data: string;
}

export interface StreamDoneMeta {
  conversationalApplied: boolean;
  correctiveApplied: boolean;
  selfRagApplied: boolean;
  ragStrategy: string;
  rerankApplied: boolean;
  hybridSearchApplied: boolean;
  multiQueryApplied: boolean;
  compressionApplied: boolean;
  semanticDedupApplied: boolean;
  longContextApplied: boolean;
}

export const queryApi = {
  query: (question: string, model?: string, useRag = true) =>
    api.post('/query', { question, model, useRag }),

  feedback: (question: string, answer: string, rating: 'UP' | 'DOWN') =>
    api.post('/query/feedback', { question, answer, rating }),

  /**
   * Streaming RAG query via POST SSE (EventSource ne supporte pas POST).
   * Yields StreamEvent objects: sources → token* → done | error.
   * The `done` event data is a JSON string containing StreamDoneMeta.
   */
  async *queryStream(
    question: string,
    useRag = true,
    signal?: AbortSignal,
    topCandidates?: number,
    conversationHistory?: { role: string; content: string }[],
    temperature?: number,
    topP?: number,
  ): AsyncGenerator<StreamEvent> {
    const body: Record<string, unknown> = { question, useRag };
    if (topCandidates !== undefined) body.topCandidates = topCandidates;
    if (temperature !== undefined) body.temperature = temperature;
    if (topP !== undefined) body.topP = topP;
    if (conversationHistory && conversationHistory.length > 0) {
      body.conversationHistory = conversationHistory;
    }
    const response = await fetch('/api/query/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal,
    });

    if (!response.ok || !response.body) {
      throw new Error(`HTTP ${response.status}`);
    }

    // ── Parser SSE ───────────────────────────────────────────────────────────
    // Le writer SSE de Spring émet les champs SANS espace après le « : »
    // (`event:token\ndata:hello\n\n`). On découpe donc sur le « : » et on prend
    // la valeur brute. On NE retire PAS l'espace optionnel de tête de la spec SSE :
    // Spring n'en ajoute pas, et un token de génération peut légitimement commencer
    // par une espace (« world ») qu'il faut préserver telle quelle.
    // Les données multi-lignes (token contenant un « \n ») sont ré-assemblées :
    // un événement est délimité par une ligne vide.
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let currentEvent = '';
    let dataLines: string[] = [];

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      let nl: number;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        let line = buffer.slice(0, nl);
        buffer = buffer.slice(nl + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1); // tolère CRLF

        if (line === '') {
          // Ligne vide → fin de l'événement courant : on l'émet.
          if (currentEvent && dataLines.length > 0) {
            yield { type: currentEvent as StreamEvent['type'], data: dataLines.join('\n') };
          }
          currentEvent = '';
          dataLines = [];
          continue;
        }
        if (line.startsWith(':')) continue; // commentaire SSE

        const colon = line.indexOf(':');
        const field = colon === -1 ? line : line.slice(0, colon);
        const val = colon === -1 ? '' : line.slice(colon + 1);
        if (field === 'event') currentEvent = val.trim();
        else if (field === 'data') dataLines.push(val);
      }
    }

    // Flush d'un éventuel dernier événement non terminé par une ligne vide.
    if (currentEvent && dataLines.length > 0) {
      yield { type: currentEvent as StreamEvent['type'], data: dataLines.join('\n') };
    }
  },
};

export const metricsApi = {
  getPersonalization: () => api.get('/metrics/personalization'),
};

export const ablationApi = {
  // Passage bloquant et lent (plusieurs appels LLM par question × bras) : timeout large.
  run: (body?: import('../types/api').AblationRequestBody) =>
    api.post('/ablation', body ?? {}, { timeout: 30 * 60 * 1000 }),
};

export const configApi = {
  getModelConfig: () => api.get('/config/model'),
  setModelConfig: (config: any) => api.post('/config/model', config),
  getModels: () => api.get('/config/models'),
  getEmbeddingConsistency: () => api.get('/config/embedding-consistency'),
  reindexCollection: (collection: string) =>
    api.post('/config/embedding-consistency/reindex', { collection }),
  getReindexStatuses: () => api.get('/config/embedding-consistency/reindex'),
};

export const modelsHubApi = {
  getRecommendations: (params: { limit?: number; memory?: string; ram?: string; cpuCores?: number } = {}) =>
    api.get('/models/hub/recommendations', { params }),
  installModel: (modelName: string, quant?: string, autoActivate = false) =>
    api.post(`/models/hub/install?modelName=${encodeURIComponent(modelName)}${quant ? `&quant=${quant}` : ''}&autoActivate=${autoActivate}`),
  getProgressSource: (modelName: string) =>
    new EventSource(`/api/models/hub/install/progress?modelName=${encodeURIComponent(modelName)}`),
};

export default api;
