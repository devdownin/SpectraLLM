import { useState, useRef, useEffect, useCallback } from 'react';
import type { FC } from 'react';
import { toast } from 'sonner';
import Tooltip from '../components/Tooltip';
import { ingestApi, datasetApi } from '../services/api';

// ── Types ────────────────────────────────────────────────────────────────────

type IngestStatus = 'UPLOADING' | 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

interface IngestEntry {
  id: string;           // local ID (Date.now + random)
  taskId: string | null;
  fileName: string;
  status: IngestStatus;
  chunksCreated: number;
  error?: string;
}

interface GenerationTask {
  taskId: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  pairsGenerated: number;
  chunksProcessed: number;
  totalChunks: number;
  error: string | null;
}

interface IngestionTask {
  taskId: string;
  status: IngestStatus;
  files: string[];
  chunksCreated: number;
  error: string | null;
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
  { key: 'ingest',    label: 'Ingest',    icon: 'cloud_upload' },
  { key: 'generate',  label: 'Generate',  icon: 'dataset' },
  { key: 'ready',     label: 'Ready',     icon: 'check_circle' },
];

interface PipelineStepProps {
  icon: string;
  label: string;
  state: 'idle' | 'active' | 'done';
  isLast?: boolean;
}

const PipelineStep: FC<PipelineStepProps> = ({ icon, label, state, isLast }) => (
  <div className="flex items-center gap-0">
    <div className="flex flex-col items-center gap-2">
      <div className={`w-10 h-10 flex items-center justify-center border transition-all ${
        state === 'done'   ? 'border-primary bg-primary/10 text-primary' :
        state === 'active' ? 'border-secondary bg-secondary/10 text-secondary animate-pulse' :
                             'border-outline-variant/30 text-outline'
      }`}>
        <span className="material-symbols-outlined text-base">{icon}</span>
      </div>
      <span className={`font-label text-[9px] uppercase tracking-widest ${
        state === 'idle' ? 'text-outline' : state === 'done' ? 'text-primary' : 'text-secondary'
      }`}>{label}</span>
    </div>
    {!isLast && (
      <div className={`w-16 h-px mb-5 mx-1 ${state === 'done' ? 'bg-primary' : 'bg-outline-variant/20'}`} />
    )}
  </div>
);

// ── Main component ───────────────────────────────────────────────────────────

const Datasets: FC = () => {
  const [dragActive, setDragActive] = useState(false);
  const [syntheticQA, setSyntheticQA] = useState(true);
  const [maxChunks, setMaxChunks] = useState(10);
  const [urlInput, setUrlInput] = useState('');
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [ingestEntries, setIngestEntries] = useState<IngestEntry[]>([]);
  const [genTask, setGenTask] = useState<GenerationTask | null>(null);
  const [stats, setStats] = useState<DatasetStats | null>(null);
  const [history, setHistory] = useState<IngestedFile[]>([]);
  const [showHistory, setShowHistory] = useState(false);

  const pollingTasks   = useRef<Set<string>>(new Set());
  const activeIntervals = useRef<Set<ReturnType<typeof setInterval>>>(new Set());

  // Cleanup all intervals on unmount (Fix 7)
  useEffect(() => () => { activeIntervals.current.forEach(clearInterval); }, []);

  // ── Stats loader — polling every 10 s ─────────────────────────────────────
  const loadStats = useCallback(async () => {
    try {
      const res = await datasetApi.getStats();
      setStats(res.data);
    } catch { /* ignore */ }
  }, []);

  const loadHistory = useCallback(async () => {
    try {
      const res = await ingestApi.getHistory();
      setHistory(res.data);
    } catch { /* ignore */ }
  }, []);

  useEffect(() => {
    loadStats();
    const id = setInterval(loadStats, 10_000);
    return () => clearInterval(id);
  }, [loadStats]);

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
        setIngestEntries(prev => {
          if (entryId === taskId) {
            return prev.map(e => e.taskId === taskId
              ? { ...e, status: t.status, chunksCreated: t.chunksCreated, error: t.error ?? undefined }
              : e
            );
          }
          return prev.map(e => e.id === entryId
            ? { ...e, status: t.status, chunksCreated: t.chunksCreated, error: t.error ?? undefined }
            : e
          );
        });
        if (t.status === 'COMPLETED' || t.status === 'FAILED') {
          clearInterval(interval);
          activeIntervals.current.delete(interval);
          pollingTasks.current.delete(taskId);
          if (t.status === 'COMPLETED') { loadStats(); loadHistory(); }
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
  }, [loadStats, loadHistory]);

  // ── Upload file ───────────────────────────────────────────────────────────
  const uploadFile = useCallback(async (file: File) => {
    const id = `${Date.now()}-${Math.random()}`;
    const entry: IngestEntry = { id, taskId: null, fileName: file.name, status: 'UPLOADING', chunksCreated: 0 };
    setIngestEntries(prev => [...prev, entry]);

    try {
      const res = await ingestApi.uploadFile(file);
      const taskId: string = res.data.taskId;
      setIngestEntries(prev => prev.map(e => e.id === id ? { ...e, taskId, status: 'PENDING' } : e));
      pollIngest(id, taskId);
    } catch (err: any) {
      const msg = err?.response?.data?.detail ?? err.message;
      setIngestEntries(prev => prev.map(e => e.id === id ? { ...e, status: 'FAILED', error: msg } : e));
      toast.error(`Ingestion échouée : ${file.name}`, { description: msg });
    }
  }, [pollIngest]);

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
      toast.error('URL invalide', { description: trimmed });
      return;
    }

    const id = `${Date.now()}-${Math.random()}`;
    const entry: IngestEntry = { id, taskId: null, fileName: trimmed, status: 'UPLOADING', chunksCreated: 0 };
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
      toast.error('Ingestion URL échouée', { description: msg });
    }
  }, [pollIngest]);

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
  useEffect(() => {
    const restoreTasks = async () => {
      loadHistory();
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
                chunksCreated: t.chunksCreated
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
  }, [pollIngest, pollGenTask, loadHistory]);

  const handleGenerateDataset = async () => {
    try {
      const res = await datasetApi.generateDataset(syntheticQA ? maxChunks : 0);
      const taskId: string = res.data.taskId;
      setGenTask({ taskId, status: 'PENDING', pairsGenerated: 0, chunksProcessed: 0, totalChunks: 0, error: null });
      pollGenTask(taskId);
      toast.success('Génération lancée', { description: `Task ${taskId.slice(0, 8)}…` });
    } catch (err: any) {
      toast.error('Erreur génération', { description: err?.response?.data?.detail ?? err.message });
    }
  };

  // ── Pipeline state derivation ─────────────────────────────────────────────
  const hasIngestedDocs = ingestEntries.some(e => e.status === 'COMPLETED')
    || (stats?.chunksInStore ?? 0) > 0;
  const genDone = genTask?.status === 'COMPLETED' || (stats?.totalPairs ?? 0) > 0;
  const genActive = genTask?.status === 'PENDING' || genTask?.status === 'PROCESSING';

  const pipelineState = (step: string): 'idle' | 'active' | 'done' => {
    if (step === 'ingest')   return hasIngestedDocs ? 'done' : ingestEntries.length > 0 ? 'active' : 'idle';
    if (step === 'generate') return genDone ? 'done' : genActive ? 'active' : 'idle';
    if (step === 'ready')    return genDone ? 'done' : 'idle';
    return 'idle';
  };

  return (
    <div className="space-y-12 animate-in fade-in duration-700">

      {/* Header */}
      <header className="flex justify-between items-end">
        <div>
          <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">Data Engineering</p>
          <h2 className="font-headline text-3xl font-bold tracking-tighter">DATASET PIPELINES</h2>
        </div>
        <div className="flex items-center gap-6">
        <button
          onClick={loadStats}
          className="flex items-center gap-1.5 text-[9px] font-label uppercase tracking-widest text-on-surface-variant hover:text-primary transition-colors"
        >
          <span className="material-symbols-outlined text-sm">refresh</span>
          Refresh
        </button>
        <div className="flex items-center gap-3">
          {PIPELINE_STEPS.map((s, i) => (
            <PipelineStep key={s.key} icon={s.icon} label={s.label}
              state={pipelineState(s.key)} isLast={i === PIPELINE_STEPS.length - 1} />
          ))}
        </div>
        </div>
      </header>

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
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant">Chunks indexés</p>
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
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant">Paires d'entraînement</p>
          </div>
        </div>
        <div className="flex items-center gap-3 justify-end">
          {!stats ? (
            <span className="text-[9px] text-outline uppercase tracking-widest font-label animate-pulse">Chargement…</span>
          ) : stats.chunksInStore === 0 ? (
            <span className="text-[9px] text-outline uppercase tracking-widest font-label border border-outline-variant/30 px-2 py-1">
              Base vide — ingérez des documents
            </span>
          ) : stats.totalPairs === 0 ? (
            <span className="text-[9px] text-secondary uppercase tracking-widest font-label border border-secondary/30 px-2 py-1">
              {stats.chunksInStore} chunks prêts — lancez l'étape 2
            </span>
          ) : (
            <span className="text-[9px] text-primary uppercase tracking-widest font-label border border-primary/30 px-2 py-1">
              Dataset prêt — {stats.totalPairs} paires
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
          <h3 className="font-headline text-lg font-bold uppercase tracking-tight">Document Ingestion</h3>
          {stats && stats.chunksInStore > 0 && (
            <span className="ml-auto font-label text-[10px] uppercase tracking-widest text-primary">
              {stats.chunksInStore} chunks in store
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
              <h4 className="font-headline text-sm font-bold mb-1 uppercase tracking-tight">Inject Raw Intelligence</h4>
              <p className="text-on-surface-variant text-xs mb-4 text-center max-w-sm">
                PDF, DOCX, TXT, JSON, XML — déposez ou cliquez pour sélectionner
              </p>
              <input ref={fileInputRef} type="file" accept=".pdf,.docx,.txt,.json,.xml" className="hidden"
                onChange={handleFileChange} multiple />
              <button
                onClick={e => { e.stopPropagation(); fileInputRef.current?.click(); }}
                className="bg-outline-variant/20 hover:bg-outline-variant/40 text-on-surface font-bold py-2 px-8 text-[10px] uppercase tracking-widest transition-colors"
              >
                Browse Files
              </button>
            </div>

            {/* URL ingestion */}
            <div className="flex items-center gap-2 bg-surface-container border border-outline-variant/20 p-3">
              <span className="material-symbols-outlined text-base text-outline shrink-0">link</span>
              <input
                type="url"
                className="flex-1 bg-transparent border-none focus:ring-0 text-xs font-body px-2 placeholder:text-outline"
                placeholder="https://example.com/document.pdf ou page web..."
                value={urlInput}
                onChange={e => setUrlInput(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') ingestUrl(urlInput); }}
              />
              <button
                onClick={() => ingestUrl(urlInput)}
                disabled={!urlInput.trim()}
                className="text-[9px] font-headline uppercase tracking-widest px-3 py-1.5 border border-primary text-primary hover:bg-primary/5 transition-colors disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
              >
                Ingest URL
              </button>
            </div>

            {/* Extraction config */}
            <div className="grid grid-cols-2 gap-4">
              <div className="bg-surface-container p-5 space-y-3">
                <div className="flex items-center gap-2">
                  <h4 className="font-headline text-xs font-bold uppercase tracking-tight">Extraction Strategy</h4>
                  <Tooltip content="Méthode de découpage des documents en chunks sémantiques.">
                    <span className="material-symbols-outlined text-xs text-outline cursor-help">help</span>
                  </Tooltip>
                </div>
                <div className="p-3 bg-surface-container-lowest border-l-2 border-primary">
                  <p className="text-xs font-bold mb-0.5">SEMANTIC_CHUNK_V2</p>
                  <p className="text-[10px] text-on-surface-variant uppercase tracking-widest leading-relaxed">
                    Context-aware partitioning
                  </p>
                </div>
              </div>

              <div className="bg-surface-container p-5 space-y-3">
                <div className="flex items-center gap-2">
                  <h4 className="font-headline text-xs font-bold uppercase tracking-tight">Augmentation</h4>
                  <Tooltip content="Active la génération de paires Q/A synthétiques à la volée.">
                    <span className="material-symbols-outlined text-xs text-outline cursor-help">help</span>
                  </Tooltip>
                </div>
                <label className="flex items-center gap-3 cursor-pointer group" onClick={() => setSyntheticQA(v => !v)}>
                  <div className="w-4 h-4 border border-primary flex items-center justify-center group-hover:bg-primary/10 transition-colors">
                    {syntheticQA && <div className="w-2 h-2 bg-primary"></div>}
                  </div>
                  <span className="text-xs font-label uppercase tracking-widest">Synthetic Q&amp;A</span>
                </label>
              </div>
            </div>
          </div>

          {/* Live ingestion list */}
          <div className="bg-surface-container p-5 flex flex-col min-h-[300px]">
            <div className="flex items-center justify-between mb-4">
              <h4 className="font-headline text-xs font-bold uppercase tracking-tight">
                {showHistory ? 'Ingestion History' : 'Live Ingestion Stream'}
              </h4>
              <button 
                onClick={() => setShowHistory(!showHistory)}
                className="text-[9px] font-label uppercase tracking-widest text-primary hover:underline flex items-center gap-1"
              >
                <span className="material-symbols-outlined text-sm">{showHistory ? 'sensors' : 'history'}</span>
                {showHistory ? 'Live' : 'History'}
              </button>
            </div>
            {showHistory ? (
              <div className="flex-1 space-y-4 overflow-y-auto custom-scrollbar">
                {history.length === 0 ? (
                  <div className="flex-1 flex items-center justify-center">
                    <p className="text-[10px] text-outline uppercase tracking-widest italic text-center">
                      Historique vide
                    </p>
                  </div>
                ) : (
                  history.map(item => (
                    <div key={item.sha256} className="p-3 bg-surface-container-lowest border-l-2 border-primary/20 hover:border-primary transition-all group">
                      <div className="flex justify-between items-start mb-1">
                        <span className="text-[10px] font-bold truncate max-w-[150px]">{item.fileName}</span>
                        <span className="text-[9px] font-mono text-outline">{item.sha256.slice(0, 8)}</span>
                      </div>
                      <div className="flex justify-between items-end">
                        <div className="flex gap-2 items-center">
                          <span className="text-[9px] font-label uppercase tracking-widest text-on-surface-variant">
                            {item.format === 'application/pdf' ? 'PDF' : 
                             item.format.includes('xml') ? 'XML' : 
                             item.format.includes('json') ? 'JSON' : 'DOC'}
                          </span>
                          <span className="w-1 h-1 rounded-full bg-outline-variant"></span>
                          <span className="text-[9px] text-outline">
                            {new Date(item.ingestedAt).toLocaleDateString()}
                          </span>
                        </div>
                        <span className="text-[10px] font-bold text-primary">{item.chunksCreated} chunks</span>
                      </div>
                    </div>
                  ))
                )}
              </div>
            ) : ingestEntries.length === 0 ? (
              <div className="flex-1 flex items-center justify-center">
                <p className="text-[10px] text-outline uppercase tracking-widest italic text-center">
                  Aucun fichier<br />en cours d'ingestion
                </p>
              </div>
            ) : (
              <div className="flex-1 space-y-3 overflow-y-auto custom-scrollbar">
                {ingestEntries.map(entry => (
                  <div key={entry.id} className="space-y-1.5">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className={`material-symbols-outlined text-sm ${statusColor[entry.status]} ${entry.status === 'PROCESSING' ? 'animate-spin' : ''}`}>
                          {statusIcon[entry.status]}
                        </span>
                        <span className="text-[10px] font-label truncate">{entry.fileName}</span>
                      </div>
                      <span className={`text-[9px] font-bold uppercase tracking-widest shrink-0 ${statusColor[entry.status]}`}>
                        {entry.status === 'COMPLETED' ? `${entry.chunksCreated} chunks` : entry.status}
                      </span>
                    </div>
                    {(entry.status === 'PROCESSING' || entry.status === 'COMPLETED') && (
                      <div className="w-full bg-outline-variant/20 h-0.5">
                        <div className={`h-full transition-all ${entry.status === 'COMPLETED' ? 'bg-primary w-full' : 'bg-secondary w-2/3 animate-pulse'}`} />
                      </div>
                    )}
                    {entry.error && (
                      <p className="text-[9px] text-error truncate">{entry.error}</p>
                    )}
                  </div>
                ))}
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
          <h3 className="font-headline text-lg font-bold uppercase tracking-tight">Dataset Generation</h3>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Controls */}
          <div className="bg-surface-container p-6 space-y-6">
            <div className="space-y-3">
              <div className="flex justify-between items-center">
                <div className="flex items-center gap-2">
                  <label className="font-label text-[10px] uppercase tracking-widest">Max Chunks</label>
                  <Tooltip content="Nombre de chunks à traiter (0 = tous). Limiter pour un test rapide.">
                    <span className="material-symbols-outlined text-xs text-outline cursor-help">help</span>
                  </Tooltip>
                </div>
                <span className="font-headline font-bold text-sm">
                  {maxChunks === 0 ? 'ALL' : maxChunks}
                </span>
              </div>
              <input
                type="range" min={0} max={100} step={5} value={maxChunks}
                onChange={e => setMaxChunks(parseInt(e.target.value))}
                className="w-full accent-primary"
              />
              <div className="flex justify-between text-[9px] text-outline uppercase tracking-widest">
                <span>All</span><span>50</span><span>100</span>
              </div>
            </div>

            <button
              onClick={handleGenerateDataset}
              disabled={genActive}
              className={`w-full font-bold py-3 px-6 text-[11px] uppercase tracking-widest transition-opacity flex items-center justify-center gap-2 ${
                genActive ? 'bg-surface-container-high text-outline cursor-not-allowed' :
                'bg-primary text-on-primary-fixed hover:opacity-90'
              }`}
            >
              <span className={`material-symbols-outlined text-sm ${genActive ? 'animate-spin' : ''}`}>
                {genActive ? 'sync' : 'rocket_launch'}
              </span>
              {genActive ? 'Generating…' : 'Initialize Pipeline'}
            </button>
          </div>

          {/* Progress */}
          <div className="lg:col-span-2 bg-surface-container p-6 space-y-5">
            {!genTask ? (
              <div className="h-full flex items-center justify-center">
                <p className="text-[10px] text-outline uppercase tracking-widest italic text-center">
                  Aucune génération en cours.<br />
                  Uploadez des documents puis lancez le pipeline.
                </p>
              </div>
            ) : (
              <>
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Task ID</p>
                    <p className="font-headline font-bold text-sm">{genTask.taskId.slice(0, 8).toUpperCase()}…</p>
                  </div>
                  <span className={`font-label text-[10px] font-bold uppercase tracking-widest px-3 py-1 border ${
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
                    <div className="flex justify-between text-[10px] font-label uppercase tracking-widest">
                      <span className="text-on-surface-variant">Chunks Processed</span>
                      <span className="font-bold">
                        {genTask.chunksProcessed} / {genTask.totalChunks}
                      </span>
                    </div>
                    <div className="w-full bg-outline-variant/20 h-1.5 relative overflow-hidden">
                      <div
                        className="absolute top-0 left-0 h-full bg-primary transition-all duration-500"
                        style={{ width: `${(genTask.chunksProcessed / genTask.totalChunks) * 100}%` }}
                      />
                    </div>
                  </div>
                )}

                {/* Pairs count */}
                <div className="grid grid-cols-2 gap-4">
                  <div className="bg-surface-container-lowest p-4 border-l-2 border-primary">
                    <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Pairs Generated</p>
                    <p className="font-headline font-bold text-2xl">{genTask.pairsGenerated}</p>
                  </div>
                  <div className="bg-surface-container-lowest p-4 border-l-2 border-secondary">
                    <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Chunks Total</p>
                    <p className="font-headline font-bold text-2xl">{genTask.totalChunks || '—'}</p>
                  </div>
                </div>

                {genTask.error && (
                  <div className="p-3 bg-error/10 border border-error/30">
                    <p className="text-[10px] text-error">{genTask.error}</p>
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
            <h3 className="font-headline text-lg font-bold uppercase tracking-tight">Dataset Ready</h3>
            <div className="w-2 h-2 bg-primary ml-1"></div>
          </div>

          {stats.totalPairs === 0 && stats.chunksInStore === 0 ? (
            <div className="bg-surface-container p-8 flex items-center justify-center">
              <p className="text-[10px] text-outline uppercase tracking-widest italic text-center">
                Aucune donnée en base.<br />
                Ingérez des documents (étape 1) puis lancez la génération (étape 2).
              </p>
            </div>
          ) : (
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
            <div className="bg-surface-container p-5 border-t-2 border-primary">
              <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Training Pairs</p>
              <p className="font-headline font-bold text-3xl">{stats.totalPairs}</p>
            </div>
            <div className="bg-surface-container p-5 border-t-2 border-secondary">
              <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Chunks in Store</p>
              <p className="font-headline font-bold text-3xl">{stats.chunksInStore}</p>
            </div>
            <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
              <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Avg Confidence</p>
              <p className="font-headline font-bold text-3xl">
                {stats.avgConfidence > 0 ? (stats.avgConfidence * 100).toFixed(0) + '%' : '—'}
              </p>
            </div>
            <div className="bg-surface-container p-5 border-t-2 border-outline-variant">
              <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-2">Categories</p>
              <p className="font-headline font-bold text-3xl">{Object.keys(stats.byCategory).length}</p>
            </div>
          </div>
          )}

          {Object.keys(stats.byCategory).length > 0 && (
            <div className="bg-surface-container p-5 space-y-3">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Category Distribution</p>
              <div className="space-y-2">
                {Object.entries(stats.byCategory).map(([cat, count]) => (
                  <div key={cat} className="space-y-1">
                    <div className="flex justify-between text-[10px] font-label uppercase tracking-widest">
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
    </div>
  );
};

export default Datasets;
