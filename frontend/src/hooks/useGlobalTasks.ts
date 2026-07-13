import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  ingestApi,
  datasetApi,
  dpoApi,
  fineTuningApi,
  evaluationApi,
  modelsHubApi,
  qualityBenchmarkApi,
} from '../services/api';

/**
 * Agrégation de TOUTES les tâches asynchrones du backend (ingestion, génération
 * de dataset, DPO, fine-tuning, évaluations, comparaisons A/B, téléchargements
 * de modèles, benchmarks qualité) sous une forme normalisée unique.
 *
 * Chaque page suit déjà SES tâches localement ; ce hook donne la vue globale
 * qui manquait : naviguer ailleurs ne fait plus « disparaître » un run en cours.
 * Le polling est adaptatif : rapide (4 s) tant qu'une tâche est active,
 * lent (30 s) sinon — même logique que InstallationHistoryPanel.
 */

export type GlobalTaskKind =
  | 'ingestion'
  | 'dataset'
  | 'dpo'
  | 'training'
  | 'evaluation'
  | 'ab'
  | 'install'
  | 'benchmark';

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
  return asArray(raw).map((t) => ({
    id: `ingestion:${t.taskId}`,
    kind: 'ingestion' as const,
    icon: 'cloud_upload',
    label: Array.isArray(t.files) && t.files.length > 0 ? t.files.join(', ') : shortId(t.taskId),
    detail: typeof t.chunksCreated === 'number' && t.chunksCreated > 0 ? `${t.chunksCreated} chunks` : null,
    status: toStatus(t.status),
    progress: null, // le backend ne fournit pas de total pour l'ingestion
    path: '/ingestion',
    error: t.error ?? null,
    timestamp: null,
  }));
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
    timestamp: null,
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
    };
  });
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
  }));
}

export const isActiveTask = (t: GlobalTask): boolean =>
  t.status === 'running' || t.status === 'pending';

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
  const { data, isLoading } = useQuery({
    queryKey: ['global-tasks'],
    queryFn: fetchGlobalTasks,
    // Poll rapide seulement quand quelque chose tourne : l'UI réagit vite sans
    // marteler l'API au repos.
    refetchInterval: (query) =>
      (query.state.data ?? []).some(isActiveTask) ? ACTIVE_POLL_MS : IDLE_POLL_MS,
    retry: false,
  });

  const tasks = useMemo(() => data ?? [], [data]);
  const activeTasks = useMemo(() => tasks.filter(isActiveTask), [tasks]);

  return { tasks, activeTasks, activeCount: activeTasks.length, isLoading };
}
