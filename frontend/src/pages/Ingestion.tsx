import { useState, useRef, useEffect, useCallback } from 'react';
import type { FC } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import Tooltip from '../components/Tooltip';
import Skeleton from '../components/Skeleton';
import ConfirmDialog from '../components/ConfirmDialog';
import { PageHeader, Button } from '../components/ui';
import { ingestApi, datasetApi } from '../services/api';
import { etaMs, formatEta } from '../hooks/useGlobalTasks';

// ── Types ────────────────────────────────────────────────────────────────────

type IngestStatus = 'UPLOADING' | 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

interface IngestEntry {
  id: string;           // local ID (Date.now + random)
  taskId: string | null;
  fileName: string;
  status: IngestStatus;
  chunksCreated: number;
  /** Total de chunks découvert au fil du chunking (0 = encore inconnu). */
  chunksExpected: number;
  error?: string;
  /** Échecs par fichier ("nom: cause") remontés par la tâche — succès partiel possible. */
  fileErrors?: string[];
}

interface GenerationTask {
  taskId: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  pairsGenerated: number;
  chunksProcessed: number;
  totalChunks: number;
  error: string | null;
  createdAt?: string;
}

interface IngestionTask {
  taskId: string;
  status: IngestStatus;
  files: string[];
  chunksCreated: number;
  chunksExpected?: number;
  error: string | null;
  fileErrors?: string[];
}

interface IngestedFile {
  sha256: string;
  fileName: string;
  format: string;
  ingestedAt: string;
  chunksCreated: number;
}

interface DatasetStats {
  totalPairs: number;
  chunksInStore: number;
  avgConfidence: number;
  byCategory: Record<string, number>;
}

// ── Status badge helpers ─────────────────────────────────────────────────────

const statusColor: Record<IngestStatus, string> = {
  UPLOADING: 'text-on-surface-variant',
  PENDING: 'text-on-surface-variant',
  PROCESSING: 'text-secondary',
  COMPLETED: 'text-primary',
  FAILED: 'text-error',
};

const statusIcon: Record<IngestStatus, string> = {
  UPLOADING: 'upload',
  PENDING: 'hourglass_empty',
  PROCESSING: 'sync',
  COMPLETED: 'check_circle',
  FAILED: 'error',
};

// ── Pipeline step indicator ──────────────────────────────────────────────────

const PIPELINE_STEPS = [
  { key: 'ingest',    labelKey: 'ingestion.stepIngest',   icon: 'cloud_upload' },
  { key: 'generate',  labelKey: 'ingestion.stepGenerate', icon: 'dataset' },
  { key: 'ready',     labelKey: 'ingestion.stepReady',    icon: 'check_circle' },
];

interface PipelineStepProps {
  icon: string;
  label: string;
  state: 'idle' | 'active' | 'done';
  nextState?: 'idle' | 'active' | 'done';
  isLast?: boolean;
}

const PipelineStep: FC<PipelineStepProps> = ({ icon, label, state, nextState, isLast }) => (
  <div className="flex items-center gap-0">
    <div className="flex flex-col items-center gap-2">
      {/* Step icon with radar rings when active */}
      <div className="relative">
        {state === 'active' && (
          <>
            <div className="absolute -inset-[6px] border border-secondary/50 ripple-ring pointer-events-none" />
            <div className="absolute -inset-[6px] border border-secondary/25 ripple-ring-delayed pointer-events-none" />
          </>
        )}
        <div className={`w-10 h-10 flex items-center justify-center border transition-all duration-500 relative z-10 ${
          state === 'done'   ? 'border-primary bg-primary/10 text-primary' :
          state === 'active' ? 'border-secondary bg-secondary/10 text-secondary' :
                               'border-outline-variant/30 text-outline'
        }`}>
          <span className="material-symbols-outlined text-base">{icon}</span>
        </div>
      </div>
      <span className={`font-label text-[10px] uppercase tracking-widest ${
        state === 'idle' ? 'text-outline' : state === 'done' ? 'text-primary' : 'text-secondary'
      }`}>{label}</span>
    </div>
    {!isLast && (
      <div className={`relative w-16 h-px mb-5 mx-1 overflow-hidden ${state === 'done' ? 'bg-primary/30' : 'bg-outline-variant/20'}`}>
        {state === 'done' && nextState !== 'active' && (
          <div className="absolute inset-0 bg-primary" />
        )}
        {/* Flow particle traveling toward the active step */}
        {state === 'done' && nextState === 'active' && (
          <div className="absolute inset-0 flow-connector" />
        )}
      </div>
    )}
  </div>
);

// ── Main component ───────────────────────────────────────────────────────────

const Ingestion: FC = () => {
  const { t, i18n } = useTranslation();
  const [dragActive, setDragActive] = useState(false);
  const [maxChunks, setMaxChunks] = useState(10);
  const [confirmGenerate, setConfirmGenerate] = useState(false);
  const [urlInput, setUrlInput] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const queryClient = useQueryClient();
  const [ingestEntries, setIngestEntries] = useState<IngestEntry[]>([]);
  const [genTask, setGenTask] = useState<GenerationTask | null>(null);
  const [history, setHistory] = useState<IngestedFile[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyPage, setHistoryPage] = useState(0);
  const [historySearch, setHistorySearch] = useState('');
  // Mirror historySearch in a ref so pollIngest can read the latest value without
  // taking it as a dependency (which would rebuild pollIngest on every keystroke and
  // re-trigger the mount-only restore effect).
  const historySearchRef = useRef(historySearch);
  historySearchRef.current = historySearch;
  const [historyLoading, setHistoryLoading] = useState(false);
  const [showHistory, setShowHistory] = useState(false);

  const pollingTasks   = useRef<Set<string>>(new Set());
  const activeIntervals = useRef<Set<ReturnType<typeof setInterval>>>(new Set());

  // Cleanup all intervals on unmount (Fix 7)
  useEffect(() => () => { activeIntervals.current.forEach(clearInterval); }, []);

  // ── Stats — React Query, polling 10 s ─────────────────────────────────────
  const { data: statsData } = useQuery({
    queryKey: ['dataset-stats'],
    queryFn: async (): Promise<DatasetStats> => (await datasetApi.getStats()).data,
    refetchInterval: 10_000,
  });
  const stats = statsData ?? null;

  // Rafraîchit les stats à la demande (post-ingestion / génération / bouton).
  const loadStats = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['dataset-stats'] });
  }, [queryClient]);

  const loadHistory = useCallback(async (page = 0, q = '', append = false) => {
    setHistoryLoading(true);
    try {
      const res = await ingestApi.getHistory({ page, size: 30, q: q || undefined });
      const data = res.data;
      const items: IngestedFile[] = data.content ?? data;
      const total: number = data.totalElements ?? items.length;
      setHistoryTotal(total);
      setHistoryPage(data.number ?? page);
      setHistory(prev => append ? [...prev, ...items] : items);
    } catch { /* ignore */ }
    finally { setHistoryLoading(false); }
  }, []);

  // ── Ingest task polling ───────────────────────────────────────────────────
  const pollIngest = useCallback((entryId: string, taskId: string) => {
    if (pollingTasks.current.has(taskId) && entryId !== taskId) return;
    pollingTasks.current.add(taskId);

    let failures = 0;
    const interval = setInterval(async () => {
      try {
        const res = await ingestApi.getTaskStatus(taskId);
        failures = 0;
        const t = res.data;
        if (t.status === 'FAILED' && t.error?.includes('OOM')) {
          toast.error(i18n.t('ingestion.oomTitle', { id: taskId.slice(0, 8) }), {
            description: i18n.t('ingestion.oomDesc'),
            duration: 10000,
          });
        }
        // Succès partiel : la tâche aboutit mais certains fichiers ont échoué (fileErrors).
        // Signalé une seule fois — le polling s'arrête à l'état terminal juste après.
        if (t.status === 'COMPLETED' && (t.fileErrors?.length ?? 0) > 0) {
          toast.warning(i18n.t('ingestion.partialTitle', { name: t.files?.[0] ?? taskId.slice(0, 8) }), {
            description: i18n.t('ingestion.partialDesc', { count: t.fileErrors!.length }),
            duration: 10000,
          });
        }
        setIngestEntries(prev => {
          const patch = {
            status: t.status,
            chunksCreated: t.chunksCreated,
            chunksExpected: t.chunksExpected ?? 0,
            error: t.error ?? undefined,
            fileErrors: t.fileErrors ?? undefined,
          };
          if (entryId === taskId) {
            return prev.map(e => e.taskId === taskId ? { ...e, ...patch } : e);
          }
          return prev.map(e => e.id === entryId ? { ...e, ...patch } : e);
        });
        if (t.status === 'COMPLETED' || t.status === 'FAILED') {
          clearInterval(interval);
          activeIntervals.current.delete(interval);
          pollingTasks.current.delete(taskId);
          if (t.status === 'COMPLETED') { loadStats(); loadHistory(0, historySearchRef.current); }
        }
      } catch {
        if (++failures >= 5) {
          clearInterval(interval);
          activeIntervals.current.delete(interval);
          pollingTasks.current.delete(taskId);
        }
      }
    }, 3000);
    activeIntervals.current.add(interval);
  }, [loadStats, loadHistory, i18n]);

  // ── Upload file ───────────────────────────────────────────────────────────
  const uploadFile = useCallback(async (file: File) => {
    const id = `${Date.now()}-${Math.random()}`;
    const entry: IngestEntry = { id, taskId: null, fileName: file.name, status: 'UPLOADING', chunksCreated: 0, chunksExpected: 0 };
    setIngestEntries(prev => [...prev, entry]);

    try {
      const res = await ingestApi.uploadFile(file);
      const taskId: string = res.data.taskId;
      setIngestEntries(prev => prev.map(e => e.id === id ? { ...e, taskId, status: 'PENDING' } : e));
      pollIngest(id, taskId);
    } catch (err: any) {
      const msg = err?.response?.data?.detail ?? err.message;
      setIngestEntries(prev => prev.map(e => e.id === id ? { ...e, status: 'FAILED', error: msg } : e));
      toast.error(i18n.t('ingestion.ingestFailed', { name: file.name }), { description: msg });
    }
  }, [pollIngest, i18n]);

  const handleDrag = (e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation();
    setDragActive(e.type === 'dragenter' || e.type === 'dragover');
  };
  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault(); e.stopPropagation();
    setDragActive(false);
    Array.from(e.dataTransfer.files).forEach(uploadFile);
  };
  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    Array.from(e.target.files ?? []).forEach(uploadFile);
    e.target.value = '';
  };

  // ── URL ingestion ─────────────────────────────────────────────────────────
  const ingestUrl = useCallback(async (url: string) => {
    const trimmed = url.trim();
    if (!trimmed) return;
    try { new URL(trimmed); } catch {
      toast.error(i18n.t('ingestion.invalidUrl'), { description: trimmed });
      return;
    }

    const id = `${Date.now()}-${Math.random()}`;
    const entry: IngestEntry = { id, taskId: null, fileName: trimmed, status: 'UPLOADING', chunksCreated: 0, chunksExpected: 0 };
    setIngestEntries(prev => [...prev, entry]);
    setUrlInput('');

    try {
      const res = await ingestApi.ingestUrls([trimmed]);
      const taskId: string = res.data.taskId;
      setIngestEntries(prev => prev.map(e => e.id === id ? { ...e, taskId, status: 'PENDING' } : e));
      pollIngest(id, taskId);
    } catch (err: any) {
      const msg = err?.response?.data?.detail ?? err.message;
      setIngestEntries(prev => prev.map(e => e.id === id ? { ...e, status: 'FAILED', error: msg } : e));
      toast.error(i18n.t('ingestion.urlIngestFailed'), { description: msg });
    }
  }, [pollIngest, i18n]);

  // ── Dataset generation ────────────────────────────────────────────────────
  const pollGenTask = useCallback((taskId: string) => {
    if (pollingTasks.current.has(taskId)) return;
    pollingTasks.current.add(taskId);

    let failures = 0;
    const interval = setInterval(async () => {
      try {
        const res = await datasetApi.getGenerationStatus(taskId);
        failures = 0;
        setGenTask(res.data);
        if (res.data.status === 'COMPLETED' || res.data.status === 'FAILED') {
          clearInterval(interval);
          activeIntervals.current.delete(interval);
          pollingTasks.current.delete(taskId);
          loadStats();
        }
      } catch {
        if (++failures >= 5) {
          clearInterval(interval);
          activeIntervals.current.delete(interval);
          pollingTasks.current.delete(taskId);
        }
      }
    }, 5000);
    activeIntervals.current.add(interval);
  }, [loadStats]);

  // ── Restore tasks on mount ────────────────────────────────────────────────
  const didRestore = useRef(false);
  useEffect(() => {
    if (didRestore.current) return;
    didRestore.current = true;
    const restoreTasks = async () => {
      loadHistory(0, '');
      try {
        // Restore Ingestion Tasks
        const ingestRes = await ingestApi.getAllTasks();
        const ingestTasks: IngestionTask[] = ingestRes.data;
        
        const newEntries: IngestEntry[] = [];
        ingestTasks.forEach(t => {
          // Only show recent or active tasks
          if (t.status === 'PROCESSING' || t.status === 'PENDING' || t.status === 'UPLOADING') {
            t.files.forEach(fileName => {
              const id = `restored-${t.taskId}-${fileName}`;
              newEntries.push({
                id,
                taskId: t.taskId,
                fileName,
                status: t.status,
                chunksCreated: t.chunksCreated,
                chunksExpected: t.chunksExpected ?? 0,
              });
            });
            pollIngest(t.taskId, t.taskId); // entryId matches taskId for restored group
          }
        });
        if (newEntries.length > 0) setIngestEntries(newEntries);

        // Restore Generation Task (most recent active one)
        const genRes = await datasetApi.getAllTasks();
        const genTasks: GenerationTask[] = genRes.data;
        const activeGenTask = genTasks
          .filter(t => t.status === 'PROCESSING' || t.status === 'PENDING')
          .sort((a, b) => b.taskId.localeCompare(a.taskId))[0]; 

        if (activeGenTask) {
          setGenTask(activeGenTask);
          pollGenTask(activeGenTask.taskId);
        }
      } catch (err) {
        console.error("Failed to restore tasks", err);
      }
    };

    restoreTasks();
    // Run exactly once on mount; pollIngest/pollGenTask/loadHistory are stable and the
    // didRestore guard prevents re-entry. Depending on them here caused the effect to
    // re-run on every history-search keystroke, clobbering results and spawning
    // duplicate pollers.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const startGeneration = async () => {
    try {
      // maxChunks=0 = préréglage « tout le corpus ».
      const res = await datasetApi.generateDataset(maxChunks);
      const taskId: string = res.data.taskId;
      setGenTask({ taskId, status: 'PENDING', pairsGenerated: 0, chunksProcessed: 0, totalChunks: 0, error: null });
      pollGenTask(taskId);
      toast.success(t('ingestion.generationStarted'), { description: t('ingestion.generationTask', { id: taskId.slice(0, 8) }) });
    } catch (err: any) {
      toast.error(t('ingestion.generationError'), { description: err?.response?.data?.detail ?? err.message });
    }
  };

  const handleGenerateDataset = () => {
    const hasActiveIngestion = ingestEntries.some(
      e => e.status === 'PENDING' || e.status === 'PROCESSING'
    );
    // Générer pendant une ingestion produit un dataset incomplet : on demande confirmation
    // (dialogue maison, cohérent avec le reste de l'app — pas de window.confirm).
    if (hasActiveIngestion) setConfirmGenerate(true);
    else startGeneration();
  };

  // ── Pipeline state derivation ─────────────────────────────────────────────
  const hasIngestedDocs = ingestEntries.some(e => e.status === 'COMPLETED')
    || (stats?.chunksInStore ?? 0) > 0;
  const genDone = genTask?.status === 'COMPLETED' || (stats?.totalPairs ?? 0) > 0;
  const genActive = genTask?.status === 'PENDING' || genTask?.status === 'PROCESSING';

  // Temps restant estimé de la génération (extrapolation linéaire depuis le lancement) ;
  // recalculé à chaque poll de la tâche (5 s), suffisant pour un compte à rebours indicatif.
  const genEta = genTask && genTask.status === 'PROCESSING' && genTask.createdAt && genTask.totalChunks > 0
    ? etaMs({
        status: 'running',
        progress: Math.min(1, genTask.chunksProcessed / genTask.totalChunks),
        startedAt: genTask.createdAt,
      }, Date.now())
    : null;

  const pipelineState = (step: string): 'idle' | 'active' | 'done' => {
    if (step === 'ingest')   return hasIngestedDocs ? 'done' : ingestEntries.length > 0 ? 'active' : 'idle';
    if (step === 'generate') return genDone ? 'done' : genActive ? 'active' : 'idle';
    if (step === 'ready')    return genDone ? 'done' : 'idle';
    return 'idle';
  };

  return (
    <div className="space-y-12 animate-in fade-in duration-700">

      {/* Header */}
      <PageHeader
        kicker={t('ingestion.kicker')}
        title={t('ingestion.title')}
        actions={
          <>
            <Button variant="ghost" size="sm" icon="refresh" onClick={loadStats}>
              {t('ingestion.refresh')}
            </Button>
            <div className="flex items-center gap-3">
              {PIPELINE_STEPS.map((s, i) => (
                <PipelineStep key={s.key} icon={s.icon} label={t(s.labelKey)}
                  state={pipelineState(s.key)}
                  nextState={i < PIPELINE_STEPS.length - 1 ? pipelineState(PIPELINE_STEPS[i + 1].key) : undefined}
                  isLast={i === PIPELINE_STEPS.length - 1} />
              ))}
            </div>
          </>
        }
      />

      {/* ── System State Banner ── */}
      <div className={`grid grid-cols-3 gap-4 p-5 border ${
        !stats ? 'border-outline-variant/20 bg-surface-container' :
        (stats.chunksInStore > 0 || stats.totalPairs > 0)
          ? 'border-primary/30 bg-primary/5'
          : 'border-outline-variant/20 bg-surface-container'
      }`}>
        <div className="flex items-center gap-3">
          <span className={`material-symbols-outlined text-base ${
            !stats ? 'text-outline animate-pulse' :
            stats.chunksInStore > 0 ? 'text-primary' : 'text-outline'
          }`}>database</span>
          <div>
            <p className="font-headline font-bold text-xl">
              {stats ? stats.chunksInStore : '—'}
            </p>
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('ingestion.indexedChunks')}</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className={`material-symbols-outlined text-base ${
            !stats ? 'text-outline' :
            stats.totalPairs > 0 ? 'text-secondary' : 'text-outline'
          }`}>dataset</span>
          <div>
            <p className="font-headline font-bold text-xl">
              {stats ? stats.totalPairs : '—'}
            </p>
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('ingestion.trainingPairs')}</p>
          </div>
        </div>
        <div className="flex items-center gap-3 justify-end">
          {!stats ? (
            <Skeleton className="h-5 w-44" />
          ) : stats.chunksInStore === 0 ? (
            <span className="text-[10px] text-outline uppercase tracking-widest font-label border border-outline-variant/30 px-2 py-1">
              {t('ingestion.emptyStore')}
            </span>
          ) : stats.totalPairs === 0 ? (
            <span className="text-[10px] text-secondary uppercase tracking-widest font-label border border-secondary/30 px-2 py-1">
              {t('ingestion.chunksReady', { count: stats.chunksInStore })}
            </span>
          ) : (
            <span className="text-[10px] text-primary uppercase tracking-widest font-label border border-primary/30 px-2 py-1">
              {t('ingestion.datasetReady', { count: stats.totalPairs })}
            </span>
          )}
        </div>
      </div>

      {/* ── Step 1: Document Ingestion ── */}
      <section className="space-y-6">
        <div className="flex items-center gap-3">
          <div className="w-6 h-6 bg-primary flex items-center justify-center">
            <span className="font-headline font-bold text-on-primary-fixed text-xs">1</span>
          </div>
          <h3 className="font-headline text-lg font-bold uppercase tracking-tight">{t('ingestion.step1Title')}</h3>
          {stats && stats.chunksInStore > 0 && (
            <span className="ml-auto font-label text-[11px] uppercase tracking-widest text-primary">
              {t('ingestion.chunksInStore', { count: stats.chunksInStore })}
            </span>
          )}
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Upload zone */}
          <div className="lg:col-span-2 space-y-4">
            <div
              className={`border-2 border-dashed p-10 flex flex-col items-center justify-center transition-all cursor-pointer ${
                dragActive ? 'border-primary bg-primary/5 scale-[1.01]' : 'border-outline-variant/30 bg-surface-container'
              }`}
              onDragEnter={handleDrag} onDragLeave={handleDrag} onDragOver={handleDrag} onDrop={handleDrop}
              onClick={() => fileInputRef.current?.click()}
            >
              <span className={`material-symbols-outlined text-4xl mb-3 transition-colors ${dragActive ? 'text-secondary animate-bounce' : 'text-primary'}`}>
                cloud_upload
              </span>
              <h4 className="font-headline text-sm font-bold mb-1 uppercase tracking-tight">{t('ingestion.dropTitle')}</h4>
              <p className="text-on-surface-variant text-xs mb-4 text-center max-w-sm">
                {t('ingestion.dropHint')}
              </p>
              <input ref={fileInputRef} type="file" accept=".pdf,.docx,.doc,.txt,.json,.xml,.htm,.html,.zip" className="hidden"
                onChange={handleFileChange} multiple />
              <button
                onClick={e => { e.stopPropagation(); fileInputRef.current?.click(); }}
                className="bg-outline-variant/20 hover:bg-outline-variant/40 text-on-surface font-bold py-2 px-8 text-[11px] uppercase tracking-widest transition-colors"
              >
                {t('ingestion.browse')}
              </button>
            </div>

            {/* URL ingestion */}
            <div className="flex items-center gap-2 bg-surface-container border border-outline-variant/20 p-3">
              <span className="material-symbols-outlined text-base text-outline shrink-0">link</span>
              <input
                type="url"
                className="flex-1 bg-transparent border-none focus:ring-0 text-xs font-body px-2 placeholder:text-outline"
                placeholder={t('ingestion.urlPlaceholder')}
                value={urlInput}
                onChange={e => setUrlInput(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') ingestUrl(urlInput); }}
              />
              <button
                onClick={() => ingestUrl(urlInput)}
                disabled={!urlInput.trim()}
                className="text-[10px] font-headline uppercase tracking-widest px-3 py-1.5 border border-primary text-primary hover:bg-primary/5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
              >
                {t('ingestion.ingestUrl')}
              </button>
            </div>

            {/* Extraction config.
                (L'ancien toggle « Synthetic Q&A » a été retiré : la génération de dataset EST
                la génération de Q/A synthétiques — la case ne servait qu'à bloquer le bouton.) */}
            <div className="bg-surface-container p-5 space-y-3">
              <div className="flex items-center gap-2">
                <h4 className="font-headline text-xs font-bold uppercase tracking-tight">{t('ingestion.extractionStrategy')}</h4>
                <Tooltip content={t('ingestion.extractionTooltip')}>
                  <span className="material-symbols-outlined text-xs text-outline cursor-help">help</span>
                </Tooltip>
              </div>
              <div className="p-3 bg-surface-container-lowest border-l-2 border-primary">
                <p className="text-xs font-bold mb-0.5">SEMANTIC_CHUNK_V2</p>
                <p className="text-[11px] text-on-surface-variant uppercase tracking-widest leading-relaxed">
                  {t('ingestion.extractionDesc')}
                </p>
              </div>
            </div>
          </div>

          {/* Live ingestion list */}
          <div className="bg-surface-container p-5 flex flex-col min-h-[300px]">
            <div className="flex items-center justify-between mb-3">
              <h4 className="font-headline text-xs font-bold uppercase tracking-tight">
                {showHistory ? t('ingestion.historyTitle') : t('ingestion.liveTitle')}
              </h4>
              <button
                onClick={() => {
                  const next = !showHistory;
                  setShowHistory(next);
                  if (next) loadHistory(0, historySearch);
                }}
                className="text-[10px] font-label uppercase tracking-widest text-primary hover:underline flex items-center gap-1"
              >
                <span className="material-symbols-outlined text-sm">{showHistory ? 'sensors' : 'history'}</span>
                {showHistory ? t('ingestion.showLive') : t('ingestion.showHistory')}
              </button>
            </div>
            {showHistory ? (
              <div className="flex-1 flex flex-col gap-3">
                {/* Search input */}
                <div className="flex items-center gap-2 bg-surface-container-lowest border border-outline-variant/20 px-2 py-1.5">
                  <span className="material-symbols-outlined text-sm text-outline shrink-0">search</span>
                  <input
                    type="text"
                    className="flex-1 bg-transparent border-none focus:ring-0 text-[11px] font-body placeholder:text-outline"
                    placeholder={t('ingestion.searchFile')}
                    value={historySearch}
                    onChange={e => {
                      setHistorySearch(e.target.value);
                      loadHistory(0, e.target.value);
                    }}
                  />
                  {historySearch && (
                    <button onClick={() => { setHistorySearch(''); loadHistory(0, ''); }}
                      className="material-symbols-outlined text-sm text-outline hover:text-error transition-colors">
                      close
                    </button>
                  )}
                </div>
                {/* Count */}
                <p className="text-[10px] text-outline font-label uppercase tracking-widest">
                  {t('ingestion.fileCount', { count: historyTotal })}{historySearch ? ` · "${historySearch}"` : ''}
                </p>
                {/* List */}
                <div className="flex-1 space-y-2 overflow-y-auto custom-scrollbar max-h-64">
                  {history.length === 0 && !historyLoading ? (
                    <p className="text-[12px] text-on-surface-variant text-center py-4">
                      {historySearch ? t('ingestion.noResults') : t('ingestion.emptyHistory')}
                    </p>
                  ) : (
                    history.map(item => (
                      <div key={item.sha256} className="p-2.5 bg-surface-container-lowest border-l-2 border-primary/20 hover:border-primary transition-all">
                        <div className="flex justify-between items-start mb-0.5">
                          <span className="text-[11px] font-bold truncate max-w-[140px]" title={item.fileName}>{item.fileName}</span>
                          <span className="text-[10px] font-mono text-outline shrink-0 ml-1">{item.sha256.slice(0, 7)}</span>
                        </div>
                        <div className="flex justify-between items-center">
                          <div className="flex gap-1.5 items-center">
                            <span className="text-[10px] font-bold font-label uppercase tracking-widest text-on-surface-variant border border-outline-variant/30 px-1">
                              {item.format?.toUpperCase() ?? '?'}
                            </span>
                            <span className="text-[10px] text-outline">
                              {new Date(item.ingestedAt).toLocaleDateString(i18n.language, { day: '2-digit', month: 'short' })}
                            </span>
                          </div>
                          <span className="text-[10px] font-bold text-primary">{item.chunksCreated}ch</span>
                        </div>
                      </div>
                    ))
                  )}
                  {historyLoading && (
                    <p className="text-[10px] text-outline font-label uppercase tracking-widest text-center animate-pulse py-2">
                      {t('ingestion.loading')}
                    </p>
                  )}
                </div>
                {/* Load more */}
                {history.length < historyTotal && !historyLoading && (
                  <button
                    onClick={() => loadHistory(historyPage + 1, historySearch, true)}
                    className="text-[10px] font-label uppercase tracking-widest text-primary border border-primary/30 px-3 py-1.5 hover:bg-primary/5 transition-colors w-full"
                  >
                    {t('ingestion.loadMore', { count: historyTotal - history.length })}
                  </button>
                )}
              </div>
            ) : ingestEntries.length === 0 ? (
              <div className="flex-1 flex items-center justify-center">
                <p className="text-[12px] text-on-surface-variant text-center">
                  {t('ingestion.noActiveIngest1')}<br />{t('ingestion.noActiveIngest2')}
                </p>
              </div>
            ) : (
              <div className="flex-1 space-y-3 overflow-y-auto custom-scrollbar">
                {ingestEntries.map(entry => {
                  // Erreurs par fichier pertinentes pour CETTE ligne : celles préfixées par
                  // son nom ("nom: cause") ; si la ligne est seule pour sa tâche (upload
                  // unitaire, URL, archive ZIP), toutes les erreurs de la tâche.
                  const siblings = entry.taskId
                    ? ingestEntries.filter(e => e.taskId === entry.taskId).length
                    : 1;
                  const entryErrors = (entry.fileErrors ?? []).filter(
                    fe => siblings <= 1 || fe.startsWith(`${entry.fileName}:`)
                  );
                  // Succès partiel : tâche COMPLETED mais ce fichier (ou cette archive) porte
                  // des erreurs — ne plus l'afficher comme un succès plein.
                  const partial = entry.status === 'COMPLETED' && entryErrors.length > 0;
                  return (
                  <div key={entry.id} className={`space-y-1.5 transition-colors duration-300 ${entry.status === 'PROCESSING' ? 'bg-secondary/3 -mx-1 px-1' : ''}`}>
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        {/* Icon: spin for PROCESSING, static otherwise */}
                        <span className={`material-symbols-outlined text-sm ${partial ? 'text-error' : statusColor[entry.status]}`}
                          style={entry.status === 'PROCESSING' ? { animation: 'rotate-slow 1.2s linear infinite' } : undefined}>
                          {partial ? 'warning' : statusIcon[entry.status]}
                        </span>
                        <span className="text-[11px] font-label truncate">{entry.fileName}</span>
                      </div>
                      <span key={`${entry.status}-${entry.chunksCreated}`}
                        className={`text-[10px] font-bold uppercase tracking-widest shrink-0 ${partial ? 'text-error' : statusColor[entry.status]} ${entry.status === 'COMPLETED' ? 'count-flash' : ''}`}>
                        {entry.status === 'COMPLETED'
                          ? (partial
                              ? t('ingestion.chunksPartial', { count: entry.chunksCreated })
                              : t('ingestion.chunks', { count: entry.chunksCreated }))
                          : entry.status === 'PROCESSING' && entry.chunksExpected > 0
                            ? t('ingestion.chunksProgress', { done: entry.chunksCreated, total: entry.chunksExpected })
                            : entry.status === 'PROCESSING' && entry.chunksCreated > 0
                              ? t('ingestion.chunksSoFar', { count: entry.chunksCreated })
                              : entry.status}
                      </span>
                    </div>
                    {(entry.status === 'PROCESSING' || entry.status === 'COMPLETED') && (
                      <div className="relative w-full bg-outline-variant/20 h-0.5 overflow-hidden">
                        {/* Barre déterminée dès que le total de chunks est connu (chunking fait),
                            sinon balayage indéterminé le temps de l'extraction. */}
                        <div className={`h-full transition-all duration-700 ${
                          entry.status === 'COMPLETED' ? (partial ? 'bg-error/60 w-full' : 'bg-primary w-full') : 'bg-secondary/50'
                        }`}
                          style={entry.status === 'PROCESSING' ? {
                            width: entry.chunksExpected > 0
                              ? `${Math.min(100, (entry.chunksCreated / entry.chunksExpected) * 100)}%`
                              : '0%',
                          } : undefined}
                        />
                        {/* Scanning beam overlay during processing */}
                        {entry.status === 'PROCESSING' && <div className="scan-beam" />}
                      </div>
                    )}
                    {entry.error && (
                      <p className="text-[10px] text-error truncate">{entry.error}</p>
                    )}
                    {entryErrors.map((fe, i) => (
                      <p key={i} className="text-[10px] text-error truncate" title={fe}>{fe}</p>
                    ))}
                  </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      </section>

      {/* ── Step 2: Dataset Generation ── */}
      <section className="space-y-6">
        <div className="flex items-center gap-3">
          <div className="w-6 h-6 bg-secondary flex items-center justify-center">
            <span className="font-headline font-bold text-on-secondary text-xs">2</span>
          </div>
          <h3 className="font-headline text-lg font-bold uppercase tracking-tight">{t('ingestion.step2Title')}</h3>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Controls */}
          <div className="bg-surface-container p-6 space-y-6">
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <label className="font-label text-[11px] uppercase tracking-widest">{t('ingestion.corpusSize')}</label>
                <Tooltip content={t('ingestion.corpusSizeTooltip')}>
                  <span className="material-symbols-outlined text-xs text-outline cursor-help">help</span>
                </Tooltip>
              </div>
              {/* Presets explicites (remplacent l'ancien slider où min = 0 = « tout » était
                  contre-intuitif) : l'option la plus lourde est clairement nommée, à droite. */}
              <div className="grid grid-cols-4 gap-2">
                {[
                  { value: 10, label: '10', sub: t('ingestion.presetQuickTest') },
                  { value: 50, label: '50', sub: t('ingestion.presetSample') },
                  { value: 100, label: '100', sub: t('ingestion.presetLarge') },
                  { value: 0, label: t('ingestion.presetAllLabel'), sub: t('ingestion.presetAll') },
                ].map(p => (
                  <button
                    key={p.value}
                    type="button"
                    onClick={() => setMaxChunks(p.value)}
                    aria-pressed={maxChunks === p.value}
                    className={`py-2 border text-center transition-all ${
                      maxChunks === p.value
                        ? 'border-primary bg-primary/10 text-primary'
                        : 'border-outline-variant/20 text-on-surface-variant hover:border-primary/30'
                    }`}
                  >
                    <span className="block font-headline font-bold text-sm">{p.label}</span>
                    <span className="block text-[9px] uppercase tracking-widest">{p.sub}</span>
                  </button>
                ))}
              </div>
              {/* Ordre de grandeur du travail : ~1 appel LLM par chunk traité. */}
              {stats && stats.chunksInStore > 0 && (
                <p className="text-[10px] text-outline uppercase tracking-widest">
                  {t('ingestion.workEstimate', {
                    count: maxChunks === 0 ? stats.chunksInStore : Math.min(maxChunks, stats.chunksInStore),
                  })}
                </p>
              )}
            </div>

            <Button
              onClick={handleGenerateDataset}
              disabled={genActive}
              size="lg"
              variant={genActive ? 'secondary' : 'primary'}
              className="w-full"
            >
              <span aria-hidden="true" className={`material-symbols-outlined text-[16px] ${genActive ? 'animate-spin' : ''}`}>
                {genActive ? 'sync' : 'rocket_launch'}
              </span>
              {genActive ? t('ingestion.generating') : t('ingestion.generate')}
            </Button>
          </div>

          {/* Progress */}
          <div className="lg:col-span-2 bg-surface-container p-6 space-y-5">
            {!genTask ? (
              <div className="h-full flex items-center justify-center">
                <p className="text-[12px] text-on-surface-variant text-center">
                  {t('ingestion.noGeneration1')}<br />
                  {t('ingestion.noGeneration2')}
                </p>
              </div>
            ) : (
              <>
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('ingestion.taskId')}</p>
                    <p className="font-headline font-bold text-sm">{genTask.taskId.slice(0, 8).toUpperCase()}…</p>
                  </div>
                  <span className={`font-label text-[11px] font-bold uppercase tracking-widest px-3 py-1 border ${
                    genTask.status === 'COMPLETED' ? 'border-primary text-primary' :
                    genTask.status === 'FAILED'    ? 'border-error text-error' :
                    genTask.status === 'PROCESSING'? 'border-secondary text-secondary' :
                                                     'border-outline text-outline'
                  }`}>
                    {genTask.status}
                  </span>
                </div>

                {/* Chunks progress */}
                {genTask.totalChunks > 0 && (
                  <div className="space-y-2">
                    <div className="flex justify-between text-[11px] font-label uppercase tracking-widest">
                      <span className="text-on-surface-variant">{t('ingestion.chunksProcessed')}</span>
                      <span className="font-bold">
                        {genTask.chunksProcessed} / {genTask.totalChunks}
                        {genEta !== null && (
                          <span className="ml-2 font-normal text-outline tabular-nums normal-case">
                            {t('taskCenter.etaLeft', { time: formatEta(genEta) })}
                          </span>
                        )}
                      </span>
                    </div>
                    <div className="relative w-full bg-outline-variant/20 h-1.5 overflow-hidden">
                      <div
                        className="absolute top-0 left-0 h-full bg-primary transition-all duration-500"
                        style={{ width: `${(genTask.chunksProcessed / genTask.totalChunks) * 100}%` }}
                      />
                      {/* Scan beam travels ahead of the fill when active */}
                      {genTask.status === 'PROCESSING' && <div className="scan-beam-primary" />}
                    </div>
                  </div>
                )}

                {/* Pairs count */}
                <div className="grid grid-cols-2 gap-4">
                  <div className="bg-surface-container-lowest p-4 border-l-2 border-primary">
                    <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('ingestion.pairsGenerated')}</p>
                    {/* key forces re-mount → count-flash animation replays on each new value */}
                    <p key={genTask.pairsGenerated} className={`font-headline font-bold text-2xl ${genTask.status === 'PROCESSING' ? 'count-flash' : ''}`}>
                      {genTask.pairsGenerated}
                    </p>
                  </div>
                  <div className="bg-surface-container-lowest p-4 border-l-2 border-secondary">
                    <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('ingestion.chunksTotal')}</p>
                    <p className="font-headline font-bold text-2xl">{genTask.totalChunks || '—'}</p>
                  </div>
                </div>

                {genTask.error && (
                  <div className="p-3 bg-error/10 border border-error/30">
                    <p className="text-[11px] text-error">{genTask.error}</p>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </section>

      {/* ── Step 3: Dataset Stats ── */}
      {stats && (
        <section className="space-y-6">
          <div className="flex items-center gap-3">
            <div className="w-6 h-6 bg-surface-container-high border border-primary flex items-center justify-center">
              <span className="font-headline font-bold text-primary text-xs">3</span>
            </div>
            <h3 className="font-headline text-lg font-bold uppercase tracking-tight">{t('ingestion.step3Title')}</h3>
            <div className="w-2 h-2 bg-primary ml-1"></div>
          </div>

          {stats.totalPairs === 0 && stats.chunksInStore === 0 ? (
            <div className="bg-surface-container p-8 flex items-center justify-center">
              <p className="text-[12px] text-on-surface-variant text-center">
                {t('ingestion.noData1')}<br />
                {t('ingestion.noData2')}
              </p>
            </div>
          ) : (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="bg-surface-container p-5 border-t-2 border-primary">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('ingestion.statPairs')}</p>
              <p className="font-headline font-bold text-3xl">{stats.totalPairs}</p>
            </div>
            <div className="bg-surface-container p-5 border-t-2 border-secondary">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('ingestion.statChunks')}</p>
              <p className="font-headline font-bold text-3xl">{stats.chunksInStore}</p>
            </div>
            <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('ingestion.statConfidence')}</p>
              <p className="font-headline font-bold text-3xl">
                {stats.avgConfidence > 0 ? (stats.avgConfidence * 100).toFixed(0) + '%' : '—'}
              </p>
            </div>
            <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('ingestion.statCategories')}</p>
              <p className="font-headline font-bold text-3xl">{Object.keys(stats.byCategory).length}</p>
            </div>
          </div>
          )}

          {Object.keys(stats.byCategory).length > 0 && (
            <div className="bg-surface-container p-5 space-y-3">
              <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('ingestion.categoryDistribution')}</p>
              <div className="space-y-2">
                {Object.entries(stats.byCategory).map(([cat, count]) => (
                  <div key={cat} className="space-y-1">
                    <div className="flex justify-between text-[11px] font-label uppercase tracking-widest">
                      <span>{cat}</span>
                      <span className="font-bold">{count}</span>
                    </div>
                    <div className="w-full bg-outline-variant/20 h-0.5">
                      <div className="h-full bg-primary" style={{ width: `${(count / stats.totalPairs) * 100}%` }} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>
      )}

      {/* Confirmation : générer pendant une ingestion produit un dataset incomplet. */}
      <ConfirmDialog
        open={confirmGenerate}
        title={t('confirm.generateWhileIngestingTitle')}
        message={t('confirm.generateWhileIngestingMessage')}
        confirmLabel={t('confirm.generateWhileIngestingConfirm')}
        danger={false}
        onCancel={() => setConfirmGenerate(false)}
        onConfirm={() => { setConfirmGenerate(false); startGeneration(); }}
      />
    </div>
  );
};

export default Ingestion;
