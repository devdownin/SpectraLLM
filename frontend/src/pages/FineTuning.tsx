import { useState, useEffect, useRef, useCallback } from 'react';
import type { FC } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import * as z from 'zod';
import { toast } from 'sonner';
import { useSse } from '../hooks/useSse';
import type { TrainingLog } from '../types/api';
import { fineTuningApi, recipeApi } from '../services/api';

// ── Types ────────────────────────────────────────────────────────────────────

type JobStatus = 'PENDING' | 'EXPORTING_DATASET' | 'TRAINING' | 'IMPORTING_MODEL' | 'COMPLETED' | 'FAILED';

interface FineTuningJob {
  jobId: string;
  status: JobStatus;
  modelName: string;
  baseModel: string;
  datasetSize: number;
  currentStep: string;
  currentEpoch: number | null;
  totalEpochs: number;
  loss: number | null;
  outputPath: string | null;
  reportPath: string | null;
  error: string | null;
  createdAt: string;
  completedAt: string | null;
  parameters: {
    loraRank: number;
    loraAlpha: number;
    epochs: number;
    learningRate: number;
    minConfidence: number;
  };
}

// ── Pipeline step definitions ─────────────────────────────────────────────

const PIPELINE_STEPS: { status: JobStatus; label: string; icon: string }[] = [
  { status: 'PENDING',           label: 'Queued',     icon: 'hourglass_empty' },
  { status: 'EXPORTING_DATASET', label: 'Export',     icon: 'dataset' },
  { status: 'TRAINING',          label: 'Training',   icon: 'model_training' },
  { status: 'IMPORTING_MODEL',   label: 'Import',     icon: 'upload_file' },
  { status: 'COMPLETED',         label: 'Complete',   icon: 'check_circle' },
];

const stepIndex = (status: JobStatus): number =>
  PIPELINE_STEPS.findIndex(s => s.status === status);

interface StepBarProps { job: FineTuningJob }

const StepBar: FC<StepBarProps> = ({ job }) => {
  const current = job.status === 'FAILED'
    ? stepIndex('COMPLETED') - 1  // show where it failed (before complete)
    : stepIndex(job.status);

  return (
    <div className="flex items-center w-full">
      {PIPELINE_STEPS.map((step, i) => {
        const isDone   = job.status !== 'FAILED' && i < current;
        const isActive = i === current && job.status !== 'COMPLETED';
        const isFailed = job.status === 'FAILED' && i === current;

        return (
          <div key={step.status} className="flex items-center flex-1 last:flex-none">
            <div className="flex flex-col items-center gap-1.5">
              <div className={`w-9 h-9 flex items-center justify-center border transition-all ${
                isFailed  ? 'border-error bg-error/10 text-error' :
                isDone    ? 'border-primary bg-primary/10 text-primary' :
                isActive  ? 'border-secondary bg-secondary/10 text-secondary' :
                            'border-outline-variant/30 text-outline'
              } ${isActive ? 'animate-pulse' : ''}`}>
                <span className="material-symbols-outlined text-sm">{step.icon}</span>
              </div>
              <span className={`font-label text-[9px] uppercase tracking-widest ${
                isFailed  ? 'text-error' :
                isDone    ? 'text-primary' :
                isActive  ? 'text-secondary' :
                            'text-outline'
              }`}>{step.label}</span>
            </div>
            {i < PIPELINE_STEPS.length - 1 && (
              <div className={`flex-1 h-px mx-1 mb-5 ${isDone ? 'bg-primary' : 'bg-outline-variant/20'}`} />
            )}
          </div>
        );
      })}
    </div>
  );
};

// ── Job status color ─────────────────────────────────────────────────────────

const jobStatusColor = (s: JobStatus) => {
  if (s === 'COMPLETED')  return 'text-primary border-primary';
  if (s === 'FAILED')     return 'text-error border-error';
  if (s === 'TRAINING')   return 'text-secondary border-secondary';
  return 'text-on-surface-variant border-outline-variant';
};

// ── Validation Schema ────────────────────────────────────────────────────────

const trainingSchema = z.object({
  modelName: z.string()
    .min(3, 'Le nom doit faire au moins 3 caractères')
    .regex(/^[a-z0-9-_]+$/, 'Lettres minuscules, chiffres, - et _ uniquement'),
  baseModel: z.string().min(1, 'Modèle de base requis'),
  epochs: z.number().min(1).max(50),
  loraRank: z.number().min(4).max(256),
  minConfidence: z.number().min(0).max(1),
  packingEnabled: z.boolean().optional(),
  dpoEnabled: z.boolean().optional(),
});

type TrainingFormValues = z.infer<typeof trainingSchema>;

interface Recipe {
  name: string;
  displayName: string;
  description: string;
}

// ── Main component ───────────────────────────────────────────────────────────

const FineTuning: FC = () => {
  const { data: newLog } = useSse<TrainingLog>('/api/sse/training-logs');
  const [logs, setLogs] = useState<TrainingLog[]>([]);
  const listRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  const [jobs, setJobs] = useState<FineTuningJob[]>([]);
  const [activeJob, setActiveJob] = useState<FineTuningJob | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const { register, handleSubmit, watch, reset, formState: { errors } } = useForm<TrainingFormValues>({
    resolver: zodResolver(trainingSchema),
    defaultValues: {
      modelName: localStorage.getItem('spectra_ft_name') || 'spectra-domain',
      baseModel: localStorage.getItem('spectra_ft_base') || 'phi3',
      epochs: parseInt(localStorage.getItem('spectra_ft_epochs') || '3'),
      loraRank: parseInt(localStorage.getItem('spectra_ft_lora') || '64'),
      minConfidence: parseFloat(localStorage.getItem('spectra_ft_conf') || '0.8'),
      packingEnabled: false,
      dpoEnabled: false,
    }
  });

  const [recipes, setRecipes] = useState<Recipe[]>([]);
  const [loadingRecipe, setLoadingRecipe] = useState(false);

  useEffect(() => {
    recipeApi.list().then(r => setRecipes(r.data ?? [])).catch(() => {});
  }, []);

  const loadRecipe = async (name: string) => {
    setLoadingRecipe(true);
    try {
      const res = await recipeApi.get(name);
      const r = res.data;
      reset({
        modelName: formValues.modelName, // keep user-chosen name
        baseModel: r.baseModel ?? 'phi3',
        epochs: r.epochs ?? 3,
        loraRank: r.loraRank ?? 64,
        minConfidence: r.minConfidence ?? 0.8,
        packingEnabled: r.packingEnabled ?? false,
        dpoEnabled: r.dpoEnabled ?? false,
      });
    } catch { /* ignore */ }
    finally { setLoadingRecipe(false); }
  };

  const exportRecipe = async () => {
    try {
      const res = await recipeApi.export({
        modelName: formValues.modelName,
        baseModel: formValues.baseModel,
        loraRank: formValues.loraRank,
        loraAlpha: formValues.loraRank * 2,
        epochs: formValues.epochs,
        learningRate: 0.0002,
        minConfidence: formValues.minConfidence,
        packingEnabled: formValues.packingEnabled ?? false,
        dpoEnabled: formValues.dpoEnabled ?? false,
      });
      const url = URL.createObjectURL(new Blob([res.data]));
      const a = document.createElement('a');
      a.href = url;
      a.download = `${formValues.modelName || 'recipe'}.yml`;
      a.click();
      URL.revokeObjectURL(url);
    } catch { /* ignore */ }
  };

  const formValues = watch();

  useEffect(() => {
    localStorage.setItem('spectra_ft_name', formValues.modelName || '');
    localStorage.setItem('spectra_ft_base', formValues.baseModel || '');
    localStorage.setItem('spectra_ft_epochs', (formValues.epochs || 3).toString());
    localStorage.setItem('spectra_ft_lora', (formValues.loraRank || 64).toString());
    localStorage.setItem('spectra_ft_conf', (formValues.minConfidence || 0.8).toString());
  }, [formValues]);

  // ── SSE logs ──────────────────────────────────────────────────────────────
  useEffect(() => {
    if (newLog) setLogs(prev => [...prev.slice(-999), newLog]);
  }, [newLog]);

  useEffect(() => {
    if (autoScroll && listRef.current) {
      listRef.current.scrollTop = listRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  // ── Load job history ──────────────────────────────────────────────────────
  const loadJobs = useCallback(async () => {
    try {
      const res = await fineTuningApi.getJobs();
      const list: FineTuningJob[] = res.data;
      setJobs(list.sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { loadJobs(); }, [loadJobs]);

  // ── Poll active job ───────────────────────────────────────────────────────
  useEffect(() => {
    if (!activeJob) return;
    if (activeJob.status === 'COMPLETED' || activeJob.status === 'FAILED') return;

    const interval = setInterval(async () => {
      try {
        const res = await fineTuningApi.getJob(activeJob.jobId);
        const job: FineTuningJob = res.data;
        setActiveJob(job);
        if (job.status === 'COMPLETED' || job.status === 'FAILED') {
          clearInterval(interval);
          loadJobs();
          if (job.status === 'COMPLETED') {
            toast.success('Fine-tuning terminé !', { description: `Modèle ${job.modelName} enregistré dans llama-server` });
          } else {
            toast.error('Fine-tuning échoué', { description: job.error ?? undefined });
          }
        }
      } catch { clearInterval(interval); }
    }, 4000);

    return () => clearInterval(interval);
  }, [activeJob?.jobId, activeJob?.status, loadJobs]);

  // ── Submit new job ────────────────────────────────────────────────────────
  const onFormSubmit = async (data: TrainingFormValues) => {
    setSubmitting(true);
    try {
      const res = await fineTuningApi.createJob(data);
      const job: FineTuningJob = res.data;
      setActiveJob(job);
      setShowForm(false);
      toast.success('Job soumis', { description: `ID: ${job.jobId.slice(0, 8)}…` });
    } catch (err: any) {
      toast.error('Erreur soumission', { description: err?.response?.data?.detail ?? err.message });
    } finally {
      setSubmitting(false);
    }
  };

  // ── Derived progress ──────────────────────────────────────────────────────
  const epochProgress = activeJob?.currentEpoch && activeJob?.totalEpochs
    ? (activeJob.currentEpoch / activeJob.totalEpochs) * 100
    : null;

  return (
    <div className="space-y-12 animate-in fade-in duration-700">

      {/* Header */}
      <header className="flex justify-between items-end">
        <div>
          <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">Architecture Evolution</p>
          <h2 className="font-headline text-3xl font-bold tracking-tighter">FINE-TUNING COMMAND</h2>
        </div>
        <button
          onClick={() => setShowForm(v => !v)}
          className="bg-primary text-on-primary-fixed font-bold py-3 px-6 text-[11px] uppercase tracking-widest hover:opacity-90 transition-opacity flex items-center gap-2"
        >
          <span className="material-symbols-outlined text-sm">{showForm ? 'close' : 'add'}</span>
          {showForm ? 'Cancel' : 'New Training Job'}
        </button>
      </header>

      {/* ── New Job Form ── */}
      {showForm && (
        <section className="bg-surface-container p-8 border-l-4 border-primary animate-in slide-in-from-top-2 duration-300">
          <div className="flex items-center justify-between mb-6">
            <h3 className="font-headline text-lg font-bold uppercase tracking-tight">Configure Training Job</h3>
            <button type="button" onClick={exportRecipe}
              className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant hover:text-on-surface flex items-center gap-1 transition-colors">
              <span className="material-symbols-outlined text-sm">download</span>Export Recipe
            </button>
          </div>

          {recipes.length > 0 && (
            <div className="mb-6 p-4 bg-surface-container-high/50 border border-outline-variant/20">
              <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-3">
                Recettes {loadingRecipe ? '— chargement…' : ''}
              </p>
              <div className="flex flex-wrap gap-2">
                {recipes.map(r => (
                  <button key={r.name} type="button" onClick={() => loadRecipe(r.name)}
                    title={r.description}
                    className="px-3 py-1.5 border border-outline-variant/40 font-label text-[10px] uppercase tracking-widest
                               hover:border-primary hover:text-primary transition-colors">
                    {r.displayName}
                  </button>
                ))}
              </div>
            </div>
          )}

          <form onSubmit={handleSubmit(onFormSubmit)} className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">

            <div className="space-y-2">
              <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Model Name</label>
              <input
                type="text" {...register('modelName')}
                className={`w-full bg-surface-container-lowest border ${errors.modelName ? 'border-error' : 'border-outline-variant/30'} px-4 py-2.5 text-sm font-label focus:outline-none focus:border-primary transition-colors`}
                placeholder="spectra-domain"
              />
              {errors.modelName && <p className="text-[9px] text-error uppercase tracking-wider">{errors.modelName.message}</p>}
            </div>

            <div className="space-y-2">
              <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Base Model</label>
              <input
                type="text" {...register('baseModel')}
                className={`w-full bg-surface-container-lowest border ${errors.baseModel ? 'border-error' : 'border-outline-variant/30'} px-4 py-2.5 text-sm font-label focus:outline-none focus:border-primary transition-colors`}
                placeholder="phi3"
              />
              {errors.baseModel && <p className="text-[9px] text-error uppercase tracking-wider">{errors.baseModel.message}</p>}
            </div>

            <div className="space-y-2">
              <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
                Epochs — <span className="text-primary font-bold">{formValues.epochs}</span>
              </label>
              <input
                type="range" min={1} max={10} {...register('epochs', { valueAsNumber: true })}
                className="w-full accent-primary mt-3"
              />
            </div>

            <div className="space-y-2">
              <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
                LoRA Rank — <span className="text-primary font-bold">{formValues.loraRank}</span>
              </label>
              <input
                type="range" min={8} max={128} step={8} {...register('loraRank', { valueAsNumber: true })}
                className="w-full accent-primary mt-3"
              />
            </div>

            <div className="space-y-2">
              <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
                Min Confidence — <span className="text-primary font-bold">{(formValues.minConfidence || 0).toFixed(1)}</span>
              </label>
              <input
                type="range" min={0.5} max={1.0} step={0.05} {...register('minConfidence', { valueAsNumber: true })}
                className="w-full accent-primary mt-3"
              />
            </div>

            <div className="space-y-3">
              <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Options</label>
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input type="checkbox" {...register('packingEnabled')} className="accent-primary" />
                <span className="font-label text-[10px] uppercase tracking-widest">Multipacking</span>
                <span className="text-[9px] text-on-surface-variant">(-30% padding)</span>
              </label>
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input type="checkbox" {...register('dpoEnabled')} className="accent-secondary" />
                <span className="font-label text-[10px] uppercase tracking-widest">DPO Alignment</span>
                <span className="text-[9px] text-on-surface-variant">(requires DPO pairs)</span>
              </label>
            </div>

            <div className="flex items-end">
              <button
                type="submit" disabled={submitting}
                className="w-full bg-primary text-on-primary-fixed font-bold py-3 px-6 text-[11px] uppercase tracking-widest hover:opacity-90 disabled:opacity-50 transition-opacity flex items-center justify-center gap-2"
              >
                <span className={`material-symbols-outlined text-sm ${submitting ? 'animate-spin' : ''}`}>
                  {submitting ? 'sync' : 'rocket_launch'}
                </span>
                {submitting ? 'Submitting…' : 'Launch Training'}
              </button>
            </div>

          </form>
        </section>
      )}

      {/* ── Active Job Monitor ── */}
      <section className="bg-surface-container p-8 relative overflow-hidden">
        <div className="absolute top-0 right-0 p-4 flex items-center gap-4">
          <button
            onClick={() => setAutoScroll(v => !v)}
            className={`text-[9px] font-bold uppercase tracking-widest px-2 py-1 border transition-colors ${
              autoScroll ? 'bg-primary/10 border-primary text-primary' : 'border-outline text-outline'
            }`}
          >
            Auto-Scroll: {autoScroll ? 'ON' : 'OFF'}
          </button>
          {activeJob && (activeJob.status !== 'COMPLETED' && activeJob.status !== 'FAILED') && (
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 bg-secondary animate-pulse"></div>
              <span className="text-[10px] font-bold text-secondary uppercase tracking-widest">Live</span>
            </div>
          )}
        </div>

        {!activeJob ? (
          <div className="py-12 flex flex-col items-center justify-center gap-3">
            <span className="material-symbols-outlined text-4xl text-outline">model_training</span>
            <p className="text-[10px] text-outline uppercase tracking-widest italic text-center">
              Aucun job actif.<br />Cliquez sur "New Training Job" pour lancer un entraînement.
            </p>
          </div>
        ) : (
          <div className="space-y-8">
            {/* Pipeline step bar */}
            <StepBar job={activeJob} />

            <div className="grid grid-cols-1 lg:grid-cols-4 gap-8">
              {/* Left: Job details */}
              <div className="lg:col-span-1 space-y-5">
                <div>
                  <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Model</p>
                  <h3 className="font-headline text-lg font-bold uppercase">{activeJob.modelName}</h3>
                  <p className="text-[10px] text-on-surface-variant">from {activeJob.baseModel}</p>
                </div>

                <div>
                  <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Status</p>
                  <span className={`font-label text-[10px] font-bold uppercase tracking-widest px-2 py-0.5 border ${jobStatusColor(activeJob.status)}`}>
                    {activeJob.status}
                  </span>
                </div>

                <div>
                  <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Current Step</p>
                  <p className="text-xs font-label">{activeJob.currentStep}</p>
                </div>

                {activeJob.datasetSize > 0 && (
                  <div>
                    <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Dataset</p>
                    <p className="font-headline font-bold text-xl">{activeJob.datasetSize} <span className="text-xs font-label text-on-surface-variant">pairs</span></p>
                  </div>
                )}

                {epochProgress !== null && (
                  <div className="space-y-2">
                    <div className="flex justify-between items-end">
                      <span className="text-[9px] font-label uppercase text-on-surface-variant">Training Progress</span>
                      <span className="font-headline font-bold text-sm">{epochProgress.toFixed(0)}%</span>
                    </div>
                    <div className="w-full bg-outline-variant/20 h-1.5 relative">
                      <div className="absolute top-0 left-0 h-full bg-secondary transition-all" style={{ width: `${epochProgress}%` }} />
                    </div>
                    <div className="flex justify-between text-[9px] text-outline">
                      <span>Epoch {activeJob.currentEpoch}/{activeJob.totalEpochs}</span>
                      {activeJob.loss !== null && <span>Loss: {activeJob.loss.toFixed(4)}</span>}
                    </div>
                  </div>
                )}

                {activeJob.error && (
                  <div className="p-3 bg-error/10 border border-error/30">
                    <p className="text-[10px] text-error break-words">{activeJob.error}</p>
                  </div>
                )}

                {activeJob.status === 'COMPLETED' && activeJob.outputPath && (
                  <div className="p-3 bg-primary/5 border border-primary/20">
                    <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Output</p>
                    <p className="text-[10px] text-primary break-all">{activeJob.outputPath}</p>
                  </div>
                )}
              </div>

              {/* Right: Telemetry stream */}
              <div className="lg:col-span-3 h-72 bg-surface-container-lowest font-mono text-[10px] flex flex-col">
                <div className="px-4 py-2.5 bg-surface-container-lowest/80 backdrop-blur-sm border-b border-outline-variant/10 flex justify-between items-center shrink-0">
                  <span className="uppercase tracking-widest text-on-surface-variant font-bold text-[9px]">Telemetry Stream</span>
                  <span className="text-[9px] text-outline">{logs.length} events</span>
                </div>
                {logs.length === 0 ? (
                  <div className="flex-1 flex items-center justify-center">
                    <p className="text-outline italic text-[10px]">Establishing telemetry uplink…</p>
                  </div>
                ) : (
                  <div ref={listRef} className="custom-scrollbar py-2 overflow-y-auto flex-1">
                    {logs.map((log, index) => (
                      <div key={index} className="flex gap-4 group px-4 border-l border-transparent hover:border-primary/30 transition-colors" style={{ height: 24, lineHeight: '24px' }}>
                        <span className="text-on-surface-variant opacity-50 min-w-[80px]">{log.timestamp}</span>
                        <span className={`font-bold min-w-[50px] ${log.level === 'ERROR' ? 'text-error' : 'text-primary'}`}>[{log.level}]</span>
                        <span className="text-on-surface group-hover:text-primary transition-colors truncate">{log.message}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        )}
      </section>

      {/* ── Training History ── */}
      <section className="space-y-6">
        <div className="flex items-center justify-between">
          <h3 className="font-headline text-xl font-bold tracking-tight uppercase">Training History</h3>
          <button
            onClick={loadJobs}
            className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant hover:text-on-surface transition-colors flex items-center gap-1"
          >
            <span className="material-symbols-outlined text-sm">refresh</span>Refresh
          </button>
        </div>

        <div className="bg-surface-container overflow-hidden">
          <table className="w-full text-left border-collapse">
            <thead>
              <tr className="bg-surface-container-high">
                {['Job ID', 'Model', 'Base', 'Dataset', 'Epochs', 'Status', 'Date'].map(h => (
                  <th key={h} className="px-5 py-3 font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-outline-variant/10">
              {jobs.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-5 py-8 text-center text-[10px] text-outline uppercase tracking-widest italic">
                    Aucun job dans l'historique
                  </td>
                </tr>
              ) : jobs.map(job => (
                <tr
                  key={job.jobId}
                  onClick={() => setActiveJob(job)}
                  className="hover:bg-surface-container-highest transition-colors cursor-pointer"
                >
                  <td className="px-5 py-3">
                    <span className="font-headline font-medium text-xs">{job.jobId.slice(0, 8).toUpperCase()}</span>
                  </td>
                  <td className="px-5 py-3 font-label text-xs uppercase">{job.modelName}</td>
                  <td className="px-5 py-3 font-label text-xs uppercase text-on-surface-variant">{job.baseModel}</td>
                  <td className="px-5 py-3 font-label text-xs">{job.datasetSize} pairs</td>
                  <td className="px-5 py-3 font-label text-xs">{job.parameters?.epochs ?? '—'}</td>
                  <td className="px-5 py-3">
                    <span className={`text-[10px] font-bold uppercase tracking-widest ${
                      job.status === 'COMPLETED' ? 'text-primary' :
                      job.status === 'FAILED'    ? 'text-error' :
                                                   'text-secondary'
                    }`}>{job.status}</span>
                  </td>
                  <td className="px-5 py-3 font-label text-xs text-on-surface-variant">
                    {job.createdAt ? new Date(job.createdAt).toLocaleDateString('fr-FR') : '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
};

export default FineTuning;
