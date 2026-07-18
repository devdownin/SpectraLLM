import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSse } from './useSse';
import {
  ingestApi,
  datasetApi,
  dpoApi,
  fineTuningApi,
  evaluationApi,
  modelsHubApi,
  qualityBenchmarkApi,
  ablationApi,
} from '../services/api';

/**
 * Agrégation de TOUTES les tâches asynchrones du backend (ingestion, génération
 * de dataset, DPO, fine-tuning, évaluations, comparaisons A/B, téléchargements
 * de modèles, benchmarks qualité) sous une forme normalisée unique.
 *
 * Chaque page suit déjà SES tâches localement ; ce hook donne la vue globale
 * qui manquait : naviguer ailleurs ne fait plus « disparaître » un run en cours.
 *
 * Source primaire : le flux SSE `/api/sse/tasks` (instantané compact poussé par le
 * backend à chaque changement d'état — latence ~2 s, une seule connexion). Les mêmes
 * normaliseurs s'appliquent au SSE et au REST, car le backend émet les mêmes noms de
 * champs. Repli : le polling REST multi-endpoints (adaptatif 4 s / 30 s) couvre la
 * fenêtre avant la première connexion et une coupure définitive du flux.
 */

export type GlobalTaskKind =
  | 'ingestion'
  | 'dataset'
  | 'dpo'
  | 'training'
  | 'evaluation'
  | 'ab'
  | 'install'
  | 'benchmark'
  | 'ablation';

export type GlobalTaskStatus = 'pending' | 'running' | 'completed' | 'failed';

export interface GlobalTask {
  /** Unique inter-sources : `${kind}:${id brut}`. */
  id: string;
  kind: GlobalTaskKind;
  /** Nom de l'icône Material Symbols. */
  icon: string;
  /** Libellé principal : fichier, modèle, id court… (donnée brute, non traduite). */
  label: string;
  /** Détail compact ("12/40", "45%", "epoch 2/3") ou null. */
  detail: string | null;
  status: GlobalTaskStatus;
  /** Progression 0..1, ou null si indéterminée. */
  progress: number | null;
  /** Route de la page qui gère ce type de tâche. */
  path: string;
  error: string | null;
  /** Horodatage le plus pertinent (fin > début > création), pour trier « Récent ». */
  timestamp: string | null;
  /** Début de la tâche (création/lancement), pour estimer le temps restant. */
  startedAt: string | null;
}

// ── Helpers de normalisation ─────────────────────────────────────────────────

const RUNNING_STATES = new Set([
  'PROCESSING', 'RUNNING', 'TRAINING', 'EXPORTING_DATASET', 'IMPORTING_MODEL',
  'DOWNLOADING', 'REGISTERING', 'UPLOADING',
]);

/** Mappe les statuts hétérogènes du backend vers les 4 états normalisés. */
export function toStatus(raw: string | null | undefined): GlobalTaskStatus {
  const s = (raw ?? '').toUpperCase();
  if (s === 'COMPLETED') return 'completed';
  if (s === 'FAILED' || s === 'CANCELLED') return 'failed';
  if (RUNNING_STATES.has(s)) return 'running';
  return 'pending';
}

/** Ratio 0..1 borné ; null si le total est inconnu ou nul. */
export function ratio(done: unknown, total: unknown): number | null {
  if (typeof done !== 'number' || typeof total !== 'number' || total <= 0) return null;
  return Math.min(1, Math.max(0, done / total));
}

const asArray = (raw: unknown): Record<string, any>[] => (Array.isArray(raw) ? raw : []);

const shortId = (id: unknown): string =>
  typeof id === 'string' && id.length > 0 ? id.slice(0, 8).toUpperCase() : '—';

const pickTimestamp = (...candidates: unknown[]): string | null => {
  for (const c of candidates) if (typeof c === 'string' && c.length > 0) return c;
  return null;
};

// ── Normaliseurs par source (exportés pour les tests) ────────────────────────

export function normalizeIngestTasks(raw: unknown): GlobalTask[] {
  return asArray(raw).map((t) => {
    // Succès partiel : une tâche COMPLETED peut porter des échecs par fichier
    // (fileErrors) — les remonter dans le champ error pour qu'ils restent visibles
    // dans le panneau global, pas seulement sur la page Ingestion.
    const fileErrors = Array.isArray(t.fileErrors) ? t.fileErrors.filter((e: unknown) => typeof e === 'string') : [];
    return {
      id: `ingestion:${t.taskId}`,
      kind: 'ingestion' as const,
      icon: 'cloud_upload',
      label: Array.isArray(t.files) && t.files.length > 0 ? t.files.join(', ') : shortId(t.taskId),
      detail: typeof t.chunksExpected === 'number' && t.chunksExpected > 0
        ? `${t.chunksCreated ?? 0}/${t.chunksExpected} chunks`
        : typeof t.chunksCreated === 'number' && t.chunksCreated > 0 ? `${t.chunksCreated} chunks` : null,
      status: toStatus(t.status),
      // Dénominateur découvert au fil du chunking (0 tant qu'inconnu → barre indéterminée).
      progress: ratio(t.chunksCreated, t.chunksExpected),
      path: '/ingestion',
      error: t.error ?? (fileErrors.length > 0 ? fileErrors.join(' · ') : null),
      timestamp: pickTimestamp(t.completedAt, t.createdAt),
      startedAt: pickTimestamp(t.createdAt),
    };
  });
}

export function normalizeDatasetTasks(raw: unknown): GlobalTask[] {
  return asArray(raw).map((t) => ({
    id: `dataset:${t.taskId}`,
    kind: 'dataset' as const,
    icon: 'dataset',
    label: shortId(t.taskId),
    detail: typeof t.totalChunks === 'number' && t.totalChunks > 0
      ? `${t.chunksProcessed ?? 0}/${t.totalChunks} chunks · ${t.pairsGenerated ?? 0} pairs`
      : null,
    status: toStatus(t.status),
    progress: ratio(t.chunksProcessed, t.totalChunks),
    path: '/ingestion',
    error: t.error ?? null,
    timestamp: pickTimestamp(t.completedAt, t.createdAt),
    startedAt: pickTimestamp(t.createdAt),
  }));
}

export function normalizeDpoTasks(raw: unknown): GlobalTask[] {
  return asArray(raw).map((t) => ({
    id: `dpo:${t.taskId}`,
    kind: 'dpo' as const,
    icon: 'balance',
    label: shortId(t.taskId),
    detail: typeof t.total === 'number' && t.total > 0 ? `${t.processed ?? 0}/${t.total}` : null,
    status: toStatus(t.status),
    progress: ratio(t.processed, t.total),
    path: '/ingestion',
    error: t.error ?? null,
    timestamp: pickTimestamp(t.completedAt, t.startedAt),
    startedAt: pickTimestamp(t.startedAt),
  }));
}

export function normalizeTrainingJobs(raw: unknown): GlobalTask[] {
  return asArray(raw).map((j) => {
    const status = toStatus(j.status);
    const epochs = typeof j.currentEpoch === 'number' && typeof j.totalEpochs === 'number' && j.totalEpochs > 0;
    return {
      id: `training:${j.jobId}`,
      kind: 'training' as const,
      icon: 'model_training',
      label: j.modelName ?? shortId(j.jobId),
      detail: epochs ? `epoch ${j.currentEpoch}/${j.totalEpochs}` : (j.currentStep ?? null),
      status,
      // La progression par époque n'a de sens que pendant l'entraînement.
      progress: status === 'running' ? ratio(j.currentEpoch, j.totalEpochs) : null,
      path: '/fine-tuning',
      error: j.error ?? null,
      timestamp: pickTimestamp(j.completedAt, j.createdAt),
      startedAt: pickTimestamp(j.createdAt),
    };
  });
}

export function normalizeEvaluations(raw: unknown): GlobalTask[] {
  return asArray(raw).map((e) => ({
    id: `evaluation:${e.evalId}`,
    kind: 'evaluation' as const,
    icon: 'grading',
    label: e.modelName ?? shortId(e.evalId),
    detail: typeof e.testSetSize === 'number' && e.testSetSize > 0
      ? `${e.processed ?? 0}/${e.testSetSize}` : null,
    status: toStatus(e.status),
    progress: ratio(e.processed, e.testSetSize),
    path: '/comparison',
    error: e.error ?? null,
    timestamp: pickTimestamp(e.completedAt, e.startedAt),
    startedAt: pickTimestamp(e.startedAt),
  }));
}

export function normalizeAbComparisons(raw: unknown): GlobalTask[] {
  return asArray(raw).map((ab) => ({
    id: `ab:${ab.abId}`,
    kind: 'ab' as const,
    icon: 'compare_arrows',
    label: ab.modelA && ab.modelB ? `${ab.modelA} vs ${ab.modelB}` : shortId(ab.abId),
    detail: typeof ab.testSetSize === 'number' && ab.testSetSize > 0
      ? `${ab.processed ?? 0}/${ab.testSetSize}` : null,
    status: toStatus(ab.status),
    progress: ratio(ab.processed, ab.testSetSize),
    path: '/comparison',
    error: ab.error ?? null,
    timestamp: pickTimestamp(ab.completedAt, ab.startedAt),
    startedAt: pickTimestamp(ab.startedAt),
  }));
}

export function normalizeInstallations(raw: unknown): GlobalTask[] {
  return asArray(raw).map((j) => {
    const status = toStatus(j.status);
    const pct = typeof j.progress === 'number' ? Math.min(100, Math.max(0, j.progress)) : null;
    return {
      id: `install:${j.jobId}`,
      kind: 'install' as const,
      icon: 'download',
      label: j.modelName ?? shortId(j.jobId),
      detail: status === 'running' && pct !== null ? `${pct}%` : (j.currentStep ?? null),
      status,
      progress: pct !== null ? pct / 100 : null,
      path: '/model-hub',
      error: j.error ?? null,
      timestamp: pickTimestamp(j.completedAt, j.createdAt),
      startedAt: pickTimestamp(j.createdAt),
    };
  });
}

export function normalizeAblationJobs(raw: unknown): GlobalTask[] {
  return asArray(raw).map((j) => ({
    id: `ablation:${j.jobId}`,
    kind: 'ablation' as const,
    icon: 'science',
    label: j.label ?? shortId(j.jobId),
    detail: typeof j.totalUnits === 'number' && j.totalUnits > 0
      ? `${j.processedUnits ?? 0}/${j.totalUnits}` : (j.currentStep ?? null),
    status: toStatus(j.status),
    progress: ratio(j.processedUnits, j.totalUnits),
    path: '/optimization',
    error: j.error ?? null,
    timestamp: pickTimestamp(j.completedAt, j.createdAt),
    startedAt: pickTimestamp(j.createdAt),
  }));
}

export function normalizeBenchmarkJobs(raw: unknown): GlobalTask[] {
  return asArray(raw).map((j) => ({
    id: `benchmark:${j.jobId}`,
    kind: 'benchmark' as const,
    icon: 'speed',
    label: j.candidate && j.baseline ? `${j.candidate} vs ${j.baseline}` : shortId(j.jobId),
    detail: null,
    status: toStatus(j.status),
    progress: null,
    path: '/model-hub',
    error: j.error ?? null,
    timestamp: pickTimestamp(j.completedAt, j.createdAt, j.startedAt),
    startedAt: pickTimestamp(j.startedAt, j.createdAt),
  }));
}

export const isActiveTask = (t: GlobalTask): boolean =>
  t.status === 'running' || t.status === 'pending';

// ── Estimation du temps restant (ETA) ────────────────────────────────────────

/**
 * Temps restant estimé (ms) par extrapolation linéaire : temps écoulé × (1−p)/p.
 * Null si la tâche ne tourne pas, n'a pas de progression exploitable ou pas de début.
 */
export function etaMs(
  task: Pick<GlobalTask, 'status' | 'progress' | 'startedAt'>,
  nowMs: number,
): number | null {
  if (task.status !== 'running' || task.progress === null || !task.startedAt) return null;
  if (task.progress <= 0 || task.progress >= 1) return null;
  const started = Date.parse(task.startedAt);
  if (Number.isNaN(started)) return null;
  const elapsed = nowMs - started;
  if (elapsed <= 0) return null;
  return elapsed * (1 - task.progress) / task.progress;
}

/** Formatage compact d'une durée : "45s", "3 min", "1 h 05". */
export function formatEta(ms: number): string {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${Math.max(s, 1)}s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  const rem = m % 60;
  return rem > 0 ? `${h} h ${String(rem).padStart(2, '0')}` : `${h} h`;
}

// ── Instantané SSE ───────────────────────────────────────────────────────────

/** Instantané compact émis par `/api/sse/tasks` (mêmes noms de champs que le REST). */
export interface TasksSnapshot {
  ingest?: unknown;
  dataset?: unknown;
  dpo?: unknown;
  training?: unknown;
  evaluations?: unknown;
  ab?: unknown;
  installs?: unknown;
  benchmarks?: unknown;
  ablations?: unknown;
}

export function normalizeSnapshot(snap: TasksSnapshot | null | undefined): GlobalTask[] {
  if (!snap || typeof snap !== 'object') return [];
  return [
    ...normalizeIngestTasks(snap.ingest),
    ...normalizeDatasetTasks(snap.dataset),
    ...normalizeDpoTasks(snap.dpo),
    ...normalizeTrainingJobs(snap.training),
    ...normalizeEvaluations(snap.evaluations),
    ...normalizeAbComparisons(snap.ab),
    ...normalizeInstallations(snap.installs),
    ...normalizeBenchmarkJobs(snap.benchmarks),
    ...normalizeAblationJobs(snap.ablations),
  ];
}

// ── Collecte best-effort ─────────────────────────────────────────────────────

/**
 * Interroge toutes les sources en parallèle ; une source en panne est ignorée
 * (les autres tâches restent visibles), cohérent avec le suivi par page.
 */
async function fetchGlobalTasks(): Promise<GlobalTask[]> {
  const sources: [() => Promise<{ data: unknown }>, (raw: unknown) => GlobalTask[]][] = [
    [() => ingestApi.getAllTasks(), normalizeIngestTasks],
    [() => datasetApi.getAllTasks(), normalizeDatasetTasks],
    [() => dpoApi.getAllTasks(), normalizeDpoTasks],
    [() => fineTuningApi.getJobs(), normalizeTrainingJobs],
    [() => evaluationApi.getAll(), normalizeEvaluations],
    [() => evaluationApi.getAllAb(), normalizeAbComparisons],
    [() => modelsHubApi.getInstallations(), normalizeInstallations],
    [() => qualityBenchmarkApi.listCompareJobs(), normalizeBenchmarkJobs],
    [() => ablationApi.listJobs(), normalizeAblationJobs],
  ];
  const results = await Promise.allSettled(sources.map(([fetch]) => fetch()));
  return results.flatMap((r, i) =>
    r.status === 'fulfilled' ? sources[i][1](r.value.data) : []
  );
}

// ── Hook ─────────────────────────────────────────────────────────────────────

const ACTIVE_POLL_MS = 4_000;
const IDLE_POLL_MS = 30_000;

export function useGlobalTasks() {
  // Source primaire : le flux SSE poussé par le backend (latence ~2 s, une connexion).
  // maxRetries élevé : le hook survit à un redémarrage de l'API (~3 min de backoff)
  // avant de basculer définitivement sur le polling.
  const { data: sseSnapshot, status: sseStatus } = useSse<TasksSnapshot>('/api/sse/tasks', { maxRetries: 12 });
  const sseTasks = useMemo(
    () => (sseSnapshot ? normalizeSnapshot(sseSnapshot) : null),
    [sseSnapshot],
  );

  // Repli REST : actif tant que le flux n'est pas ouvert (démarrage, reconnexion,
  // abandon après épuisement des tentatives). Poll rapide seulement quand quelque
  // chose tourne : l'UI réagit vite sans marteler l'API au repos.
  const pollingEnabled = sseStatus !== 'open';
  const { data: polledTasks, isLoading: pollLoading } = useQuery({
    queryKey: ['global-tasks'],
    queryFn: fetchGlobalTasks,
    enabled: pollingEnabled,
    refetchInterval: (query) =>
      (query.state.data ?? []).some(isActiveTask) ? ACTIVE_POLL_MS : IDLE_POLL_MS,
    retry: false,
  });

  const tasks = useMemo(() => {
    if (sseStatus === 'open' && sseTasks) return sseTasks;
    // Flux coupé : le polling reprend ; en attendant sa 1re réponse, on garde le
    // dernier instantané SSE plutôt que de vider le panneau.
    return polledTasks ?? sseTasks ?? [];
  }, [sseStatus, sseTasks, polledTasks]);

  const activeTasks = useMemo(() => tasks.filter(isActiveTask), [tasks]);

  return {
    tasks,
    activeTasks,
    activeCount: activeTasks.length,
    isLoading: !sseSnapshot && pollLoading,
    /** 'open' = flux temps réel actif ; sinon repli sur le polling REST. */
    liveStatus: sseStatus,
  };
}
