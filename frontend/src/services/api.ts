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
  cancelTask: (taskId: string) => api.delete(`/ingest/${taskId}`),
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
  cancelGeneration: (taskId: string) => api.delete(`/dataset/generate/${taskId}`),
};

export const fineTuningApi = {
  getJobs: () => api.get('/fine-tuning'),
  getJob: (jobId: string) => api.get(`/fine-tuning/${jobId}`),
  createJob: (job: any) => api.post('/fine-tuning', job),
  cancelJob: (jobId: string) => api.delete(`/fine-tuning/${jobId}`),
  getModels: () => api.get('/fine-tuning/models'),
  getBaseModels: () => api.get('/fine-tuning/base-models'),
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
  cancel: (evalId: string) => api.delete(`/evaluation/${evalId}`),
  cancelAb: (abId: string) => api.delete(`/evaluation/ab/${abId}`),
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
  cancelTask: (taskId: string) => api.delete(`/dataset/dpo/generate/${taskId}`),
  getStats: () => api.get('/dataset/dpo/stats'),
  /**
   * Enregistre une préférence A/B du Playground comme paire DPO.
   * `chosen` = réponse préférée, `rejected` = l'autre ; `source` trace le module comparé.
   */
  recordPreference: (pref: { prompt: string; chosen: string; rejected: string; source: string }) =>
    api.post('/dataset/dpo/preference', pref),
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
  type: 'sources' | 'token' | 'done' | 'error' | 'stage' | 'replace';
  data: string;
}

/** Événement SSE `stage` : étape du pipeline RAG en cours d'exécution côté backend. */
export interface StreamStageInfo {
  stage: string;
  /** Itération de la boucle agentique (stage `agentic_search`). */
  iteration?: number;
  /** Requête reformulée par le LLM (stage `agentic_search`). */
  query?: string;
}

/** Étape du pipeline mesurée côté serveur (timeline du panneau Trace). */
export interface StreamStageTrace {
  stage: string;
  /** Durée serveur de l'étape en millisecondes. */
  durationMs: number;
  /** Cardinalité en entrée (ex. chunks avant filtrage), absente si non pertinente. */
  inCount?: number;
  /** Cardinalité en sortie (ex. chunks après filtrage), absente si non pertinente. */
  outCount?: number;
  /** Précision optionnelle (stratégie retenue, scores de réflexion…). */
  detail?: string;
}

/**
 * Surcharges par requête des modules RAG (tri-état) : `false` force le module OFF pour la
 * requête, `null`/absent = défaut de déploiement. On ne force jamais ON (un module absent
 * du serveur ne peut pas être activé). Alimenté par les toggles et la comparaison A/B.
 */
export interface RagOverridesDto {
  adaptive?: boolean | null;
  conversational?: boolean | null;
  multiQuery?: boolean | null;
  hybrid?: boolean | null;
  rerank?: boolean | null;
  corrective?: boolean | null;
  compression?: boolean | null;
  selfRag?: boolean | null;
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
  /** Nombre de chunks injectés dans le contexte final envoyé au LLM. */
  chunkCount?: number;
  /** Question autonome utilisée pour le retrieval (Conversational RAG), absente si non reformulée. */
  rewrittenQuestion?: string;
  /** Itérations de recherche de la boucle agentique (stratégie AGENTIC). */
  agenticIterations?: number;
  /** Raison d'arrêt de la boucle agentique (ANSWER, MAX_ITERATIONS, NO_NEW_CHUNKS, FORMAT_ERROR). */
  agenticStopReason?: string;
  /** Scores de réflexion Self-RAG « ISREL/ISSUP/ISUSE », absents si non évalué. */
  selfRagScores?: string;
  /** Chronologie des étapes du pipeline (durée serveur + compteurs), pour la timeline du Trace. */
  stages?: StreamStageTrace[];
  /** Taille (caractères) du contexte récupéré injecté dans le prompt — budget d'entrée estimé (~4 c/token). */
  contextChars?: number;
}

/** Décompte 👍/👎 pour une strate (stratégie ou module). */
export interface RatingCounts {
  up: number;
  down: number;
}

/** Agrégats du feedback Playground renvoyés par `GET /query/feedback/stats`. */
export interface FeedbackStats {
  total: number;
  up: number;
  down: number;
  /** Taux de 👎 global (0–1). */
  downRate: number;
  byStrategy: Record<string, RatingCounts>;
  byModule: Record<string, RatingCounts>;
}

export const queryApi = {
  query: (question: string, model?: string, useRag = true) =>
    api.post('/query', { question, model, useRag }),

  feedback: (
    question: string, answer: string, rating: 'UP' | 'DOWN',
    ragMeta?: Record<string, unknown>, overrides?: RagOverridesDto,
  ) =>
    api.post('/query/feedback', { question, answer, rating, ragMeta, overrides }),

  /** Agrégats du feedback Playground (taux de 👎 par stratégie et par module). */
  getFeedbackStats: () => api.get<FeedbackStats>('/query/feedback/stats'),

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
    overrides?: RagOverridesDto,
  ): AsyncGenerator<StreamEvent> {
    const body: Record<string, unknown> = { question, useRag };
    if (topCandidates !== undefined) body.topCandidates = topCandidates;
    if (temperature !== undefined) body.temperature = temperature;
    if (topP !== undefined) body.topP = topP;
    if (conversationHistory && conversationHistory.length > 0) {
      body.conversationHistory = conversationHistory;
    }
    // N'inclure les surcharges que si au moins une est posée (sinon = défaut serveur).
    if (overrides && Object.values(overrides).some(v => v === false || v === true)) {
      body.overrides = overrides;
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
  // Le passage est lent (plusieurs appels LLM par question × bras) : il est piloté comme un
  // job asynchrone suivi (progression réelle, annulable, rapport persisté côté serveur) —
  // fini l'appel bloquant de 30 minutes dont le résultat se perdait au moindre refresh.
  runAsync: (body?: import('../types/api').AblationRequestBody) =>
    api.post('/ablation/async', body ?? {}),
  getJob: (jobId: string) => api.get(`/ablation/jobs/${jobId}`),
  listJobs: () => api.get('/ablation/jobs'),
  cancelJob: (jobId: string) => api.delete(`/ablation/jobs/${jobId}`),
};

/** Disponibilité serveur des modules RAG (bean déployé) renvoyée par `GET /config/rag`. */
export interface RagModuleConfig {
  modules: Record<string, boolean>;
}

export const configApi = {
  getModelConfig: () => api.get('/config/model'),
  setModelConfig: (config: any) => api.post('/config/model', config),
  getModels: () => api.get('/config/models'),
  getEmbeddingConsistency: () => api.get('/config/embedding-consistency'),
  reindexCollection: (collection: string) =>
    api.post('/config/embedding-consistency/reindex', { collection }),
  getReindexStatuses: () => api.get('/config/embedding-consistency/reindex'),
  /** État réel des modules RAG côté serveur (module → déployé ou non). */
  getRagConfig: () => api.get<RagModuleConfig>('/config/rag'),
};

export const qualityBenchmarkApi = {
  // Comparaison qualité asynchrone (suivie) : candidate vs baseline sur le benchmark tenu à l'écart.
  compareAsync: (baseline: string, candidate: string) =>
    api.post(`/quality-benchmark/compare/async?baseline=${encodeURIComponent(baseline)}&candidate=${encodeURIComponent(candidate)}`),
  getCompareJob: (jobId: string) => api.get(`/quality-benchmark/compare/${encodeURIComponent(jobId)}`),
  listCompareJobs: () => api.get('/quality-benchmark/compare'),
};

export const modelsHubApi = {
  getRecommendations: (params: { limit?: number; memory?: string; ram?: string; cpuCores?: number } = {}) =>
    api.get('/models/hub/recommendations', { params }),
  installModel: (modelName: string, quant?: string, autoActivate = false) =>
    api.post(`/models/hub/install?modelName=${encodeURIComponent(modelName)}${quant ? `&quant=${quant}` : ''}&autoActivate=${autoActivate}`),
  getProgressSource: (modelName: string) =>
    new EventSource(`/api/models/hub/install/progress?modelName=${encodeURIComponent(modelName)}`),
  getInstallations: () => api.get('/models/hub/installations'),
  cancelInstallation: (jobId: string) => api.delete(`/models/hub/installations/${jobId}`),
  getStorage: () => api.get('/models/hub/storage'),
  purgeLlmfitCache: () => api.post('/models/hub/storage/llmfit-cache/purge'),
  deleteOrphanFile: (file: string) =>
    api.delete('/models/hub/storage/files', { params: { file } }),
  deleteModel: (name: string, type = 'chat', deleteFile = true) =>
    api.delete(`/fine-tuning/models/${encodeURIComponent(name)}`, { params: { type, deleteFile } }),
};

export default api;
