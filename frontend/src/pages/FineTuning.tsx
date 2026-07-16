import { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import type { FC } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import * as z from 'zod';
import { toast } from 'sonner';
import { useSse } from '../hooks/useSse';
import { useGlobalTasks, etaMs, formatEta } from '../hooks/useGlobalTasks';
import type { TrainingLog } from '../types/api';
import { configApi, fineTuningApi, recipeApi, datasetApi, dpoApi } from '../services/api';
import { resolveTrainableBase, shouldReplace, suggestModelName } from '../lib/fineTuningPrefill';
import LossChart from '../components/charts/LossChart';

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

const PIPELINE_STEPS: { status: JobStatus; icon: string }[] = [
  { status: 'PENDING',           icon: 'hourglass_empty' },
  { status: 'EXPORTING_DATASET', icon: 'dataset' },
  { status: 'TRAINING',          icon: 'model_training' },
  { status: 'IMPORTING_MODEL',   icon: 'upload_file' },
  { status: 'COMPLETED',         icon: 'check_circle' },
];

const stepIndex = (status: JobStatus): number =>
  PIPELINE_STEPS.findIndex(s => s.status === status);

interface StepBarProps { job: FineTuningJob }

const StepBar: FC<StepBarProps> = ({ job }) => {
  const { t } = useTranslation();
  const current = job.status === 'FAILED'
    ? stepIndex('COMPLETED') - 1
    : stepIndex(job.status);

  return (
    <div className="flex items-center w-full">
      {PIPELINE_STEPS.map((step, i) => {
        const isDone   = job.status !== 'FAILED' && i < current;
        const isActive = i === current && job.status !== 'COMPLETED';
        const isFailed = job.status === 'FAILED' && i === current;
        const isTraining = isActive && step.status === 'TRAINING';
        // Flow connector: the connector right before the active step
        const isFlowConnector = isDone && i === current - 1 && job.status !== 'COMPLETED' && job.status !== 'FAILED';

        return (
          <div key={step.status} className="flex items-center flex-1 last:flex-none">
            <div className="flex flex-col items-center gap-1.5">
              <div className="relative">
                {/* Double radar rings for active step */}
                {isActive && (
                  <>
                    <div className="absolute -inset-[6px] border border-secondary/55 ripple-ring pointer-events-none" />
                    <div className="absolute -inset-[6px] border border-secondary/25 ripple-ring-delayed pointer-events-none" />
                  </>
                )}
                {/* Slow orbit ring for TRAINING specifically */}
                {isTraining && (
                  <div className="absolute -inset-[4px] border-t border-primary/50 orbit-ring pointer-events-none" />
                )}
                <div className={`w-9 h-9 flex items-center justify-center border transition-all relative z-10 ${
                  isFailed  ? 'border-error bg-error/10 text-error' :
                  isDone    ? 'border-primary bg-primary/10 text-primary' :
                  isActive  ? 'border-secondary bg-secondary/10 text-secondary' :
                              'border-outline-variant/30 text-outline'
                }`}>
                  <span className="material-symbols-outlined text-sm">{step.icon}</span>
                </div>
              </div>
              <span className={`font-label text-[10px] uppercase tracking-widest ${
                isFailed  ? 'text-error' :
                isDone    ? 'text-primary' :
                isActive  ? 'text-secondary' :
                            'text-outline'
              }`}>{t(`fineTuning.steps.${step.status}`)}</span>
            </div>
            {i < PIPELINE_STEPS.length - 1 && (
              <div className={`relative flex-1 h-px mx-1 mb-5 overflow-hidden ${
                isDone ? 'bg-primary/30' : 'bg-outline-variant/20'
              }`}>
                {/* Solid primary fill for fully completed segments */}
                {isDone && !isFlowConnector && (
                  <div className="absolute inset-0 bg-primary" />
                )}
                {/* Animated flow particle on the segment leading to active step */}
                {isFlowConnector && (
                  <div className="absolute inset-0 flow-connector" />
                )}
              </div>
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
// Construit avec t() pour que les messages de validation suivent la langue.

const makeTrainingSchema = (t: TFunction) => z.object({
  modelName: z.string()
    .min(3, t('fineTuning.nameMin'))
    .regex(/^[a-z0-9-_]+$/, t('fineTuning.namePattern')),
  baseModel: z.string().min(1, t('fineTuning.baseRequired')),
  epochs: z.number().min(1).max(50),
  loraRank: z.number().min(4).max(256),
  minConfidence: z.number().min(0).max(1),
  packingEnabled: z.boolean().optional(),
  dpoEnabled: z.boolean().optional(),
  exportGguf: z.boolean().optional(),
});

type TrainingFormValues = z.infer<ReturnType<typeof makeTrainingSchema>>;

interface Recipe {
  name: string;
  displayName: string;
  description: string;
}

// ── Main component ───────────────────────────────────────────────────────────

const FineTuning: FC = () => {
  const { t, i18n } = useTranslation();
  const { data: newLog, status: sseStatus } = useSse<TrainingLog>('/api/sse/training-logs');
  const [logs, setLogs] = useState<TrainingLog[]>([]);
  const listRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  const [jobs, setJobs] = useState<FineTuningJob[]>([]);
  const [activeJob, setActiveJob] = useState<FineTuningJob | null>(null);
  const [showForm, setShowForm] = useState(false);
  const jobRestoredRef = useRef(false);
  const [submitting, setSubmitting] = useState(false);
  const [lossHistory, setLossHistory] = useState<{ epoch: number; loss: number }[]>([]);

  const trainingSchema = useMemo(() => makeTrainingSchema(t), [t]);

  const { register, handleSubmit, watch, reset, setValue, getValues, formState: { errors } } = useForm<TrainingFormValues>({
    resolver: zodResolver(trainingSchema),
    defaultValues: {
      modelName: localStorage.getItem('spectra_ft_name') || 'spectra-domain',
      baseModel: localStorage.getItem('spectra_ft_base') || 'phi3',
      epochs: parseInt(localStorage.getItem('spectra_ft_epochs') || '3'),
      loraRank: parseInt(localStorage.getItem('spectra_ft_lora') || '64'),
      minConfidence: parseFloat(localStorage.getItem('spectra_ft_conf') || '0.8'),
      packingEnabled: false,
      dpoEnabled: false,
      exportGguf: false,
    }
  });

  const [recipes, setRecipes] = useState<Recipe[]>([]);
  const [loadingRecipe, setLoadingRecipe] = useState(false);

  useEffect(() => {
    recipeApi.list().then(r => setRecipes(r.data ?? [])).catch(() => {});
  }, []);

  // ── Modèle actif → affichage + préremplissage des champs de nom ────────────
  // Le GGUF actuellement servi n'est PAS ré-entraînable : le champ baseModel doit
  // référencer une base du catalogue (base_models.json) ou un repo HF. On affiche
  // donc le modèle actif et on en dérive : (1) un nom suggéré pour le modèle
  // fine-tuné, (2) la base entraînable la plus plausible — métadonnée baseModel
  // d'un modèle déjà fine-tuné, correspondance hfRepo, ou alias du catalogue
  // contenu dans le nom. Une valeur SAISIE par l'utilisateur n'est jamais écrasée :
  // seuls les défauts génériques et nos propres suggestions précédentes le sont.
  const [activeModel, setActiveModel] = useState('');
  const [suggestedBase, setSuggestedBase] = useState('');
  /** Catalogue des bases entraînables (base_models.json) : alimente la datalist du champ. */
  const [baseCatalog, setBaseCatalog] = useState<{ alias: string; hfRepo?: string; description?: string }[]>([]);

  useEffect(() => {
    (async () => {
      try {
        const [cfgRes, modelsRes, catalogRes] = await Promise.all([
          configApi.getModelConfig(),
          configApi.getModels().catch(() => ({ data: [] as any[] })),
          fineTuningApi.getBaseModels().catch(() => ({ data: [] as any[] })),
        ]);
        const catalog: any[] = Array.isArray(catalogRes.data) ? catalogRes.data : [];
        setBaseCatalog(catalog.filter(c => c?.alias));

        const active: string = cfgRes.data?.model ?? '';
        if (!active) return;
        setActiveModel(active);

        const registry: any[] = Array.isArray(modelsRes.data) ? modelsRes.data : [];
        const base = resolveTrainableBase(active, registry, catalog);
        if (base) setSuggestedBase(base);

        const suggestedName = suggestModelName(active);
        const prevName = localStorage.getItem('spectra_ft_suggested_name');
        if (shouldReplace(getValues('modelName'), 'spectra-domain', prevName)) {
          setValue('modelName', suggestedName, { shouldValidate: true });
        }
        localStorage.setItem('spectra_ft_suggested_name', suggestedName);

        if (base) {
          const prevBase = localStorage.getItem('spectra_ft_suggested_base');
          if (shouldReplace(getValues('baseModel'), 'phi3', prevBase)) {
            setValue('baseModel', base, { shouldValidate: true });
          }
          localStorage.setItem('spectra_ft_suggested_base', base);
        }
      } catch { /* API indisponible : le formulaire garde ses défauts */ }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
    } catch {
      // Action utilisateur explicite : signaler l'échec plutôt que de ne rien faire.
      toast.error(t('fineTuning.recipeLoadFailed'));
    }
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
    } catch {
      toast.error(t('fineTuning.exportRecipeFailed'));
    }
  };

  const formValues = watch();

  useEffect(() => {
    localStorage.setItem('spectra_ft_name', formValues.modelName || '');
    localStorage.setItem('spectra_ft_base', formValues.baseModel || '');
    localStorage.setItem('spectra_ft_epochs', (formValues.epochs || 3).toString());
    localStorage.setItem('spectra_ft_lora', (formValues.loraRank || 64).toString());
    localStorage.setItem('spectra_ft_conf', (formValues.minConfidence || 0.8).toString());
  }, [formValues]);

  // ── SSE logs + loss extraction ────────────────────────────────────────────
  // Mirror the current epoch in a ref so this effect depends ONLY on newLog. Depending on
  // currentEpoch made it re-run on every epoch tick, re-appending the same log line and
  // recording the previous message's loss under the new epoch (phantom loss point).
  const currentEpochRef = useRef<number | null | undefined>(activeJob?.currentEpoch);
  currentEpochRef.current = activeJob?.currentEpoch;
  useEffect(() => {
    if (!newLog) return;
    setLogs(prev => [...prev.slice(-999), newLog]);
    // Parse "loss: 0.1234" or "loss=0.1234" from log messages
    const m = newLog.message.match(/loss[=:\s]+([0-9]+\.[0-9]+)/i);
    const epoch = currentEpochRef.current;
    if (m && epoch) {
      const loss = parseFloat(m[1]);
      setLossHistory(prev => {
        const last = prev[prev.length - 1];
        if (last?.epoch === epoch) return [...prev.slice(0, -1), { epoch, loss }];
        return [...prev, { epoch, loss }];
      });
    }
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
      const sorted = list.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
      setJobs(sorted);
      // Restore active job once on initial load (page reload while a job is running)
      if (!jobRestoredRef.current && sorted.length > 0) {
        jobRestoredRef.current = true;
        const inFlight = sorted.find(j =>
          j.status !== 'COMPLETED' && j.status !== 'FAILED');
        if (inFlight) setActiveJob(inFlight);
      }
    } catch { /* ignore */ }
  }, []);

  useEffect(() => { loadJobs(); }, [loadJobs]);

  // ── Rafraîchissement du job actif piloté par le suivi global ────────────────
  // Remplace l'ancien setInterval 4 s qui abandonnait en silence après 5 échecs :
  // le hook global (flux SSE + repli polling adaptatif, reconnexion incluse) signale
  // les changements d'état ; on ne re-fetch le job complet (loss, datasetSize,
  // outputPath — absents de l'instantané compact) que lorsque quelque chose a bougé.
  const { tasks: globalTasks } = useGlobalTasks();
  const activeGlobal = activeJob
    ? globalTasks.find(task => task.id === `training:${activeJob.jobId}`)
    : undefined;

  useEffect(() => {
    if (!activeJob) return;
    if (activeJob.status === 'COMPLETED' || activeJob.status === 'FAILED') return;
    let cancelled = false;

    (async () => {
      try {
        const res = await fineTuningApi.getJob(activeJob.jobId);
        if (cancelled) return;
        const job: FineTuningJob = res.data;
        setActiveJob(job);
        // Accumulate loss from the fetched job when SSE logs don't carry it
        if (job.loss !== null && job.currentEpoch !== null) {
          setLossHistory(prev => {
            const epoch = job.currentEpoch!;
            const last = prev[prev.length - 1];
            if (last?.epoch === epoch) return [...prev.slice(0, -1), { epoch, loss: job.loss! }];
            if (prev.some(p => p.epoch === epoch)) return prev;
            return [...prev, { epoch, loss: job.loss! }];
          });
        }
        if (job.status === 'COMPLETED' || job.status === 'FAILED') {
          loadJobs();
          if (job.status === 'COMPLETED') {
            // Le job produit un adaptateur LoRA sur disque ; l'export GGUF + l'enregistrement
            // dans llama-server sont des étapes distinctes (ne pas prétendre qu'il est déployé).
            toast.success(i18n.t('fineTuning.complete'), {
              description: i18n.t('fineTuning.completeDesc', { name: job.modelName }),
            });
          } else {
            toast.error(i18n.t('fineTuning.failed'), { description: job.error ?? undefined });
          }
        }
      } catch {
        // Erreur transitoire (API occupée pendant l'entraînement CPU) : le prochain
        // changement d'état signalé par le suivi global re-déclenchera un fetch.
      }
    })();

    return () => { cancelled = true; };
    // Dépendances = les champs COMPACTS du suivi global (valeurs primitives, stables
    // entre deux instantanés identiques) : le fetch ne part que sur un vrai changement.
    // L'objet activeJob entier est volontairement exclu : chaque fetch le remplace
    // (nouvelle identité) et l'inclure transformerait l'effet en boucle de polling.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeJob?.jobId, activeJob?.status,
      activeGlobal?.status, activeGlobal?.detail, activeGlobal?.error,
      loadJobs, i18n]);

  // Horloge pour l'ETA : le suivi global n'émet que sur changement d'état (une fois
  // par époque au mieux) — il ne suffit pas à faire vivre un compte à rebours.
  const jobRunning = !!activeJob && activeJob.status !== 'COMPLETED' && activeJob.status !== 'FAILED';
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!jobRunning) return;
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [jobRunning]);

  // Temps restant estimé par extrapolation linéaire sur les époques (constat #17 :
  // avec 3 époques la barre saute 0→33→66→100 % — l'ETA comble entre deux sauts).
  const eta = jobRunning && activeJob!.currentEpoch && activeJob!.totalEpochs
    ? etaMs({
        status: 'running',
        progress: Math.min(1, activeJob!.currentEpoch / activeJob!.totalEpochs),
        startedAt: activeJob!.createdAt,
      }, now)
    : null;

  // ── Submit new job ────────────────────────────────────────────────────────
  const onFormSubmit = async (data: TrainingFormValues) => {
    setSubmitting(true);
    try {
      // Garde-fou pré-lancement (constat #9) : sur dataset vide, le job échouerait quelques
      // secondes après sa création côté serveur — bloquer ici avec un message actionnable.
      // Vérification best-effort : si elle échoue (réseau…), on laisse le backend trancher.
      try {
        if (data.dpoEnabled) {
          const dpo = (await dpoApi.getStats()).data;
          if ((dpo?.totalPairs ?? 0) === 0) {
            toast.error(t('fineTuning.guardDpoEmpty'), { description: t('fineTuning.guardDpoEmptyDesc') });
            return;
          }
        } else {
          const stats = (await datasetApi.getStats()).data;
          if ((stats?.totalPairs ?? 0) === 0) {
            toast.error(t('fineTuning.guardDatasetEmpty'), { description: t('fineTuning.guardDatasetEmptyDesc') });
            return;
          }
        }
      } catch { /* garde-fou best-effort — le backend reste la source de vérité */ }

      const res = await fineTuningApi.createJob(data);
      const job: FineTuningJob = res.data;
      setActiveJob(job);
      setLossHistory([]);
      setShowForm(false);
      toast.success(t('fineTuning.submitted'), { description: t('fineTuning.submittedId', { id: job.jobId.slice(0, 8) }) });
    } catch (err: any) {
      // 409 = un entraînement tourne déjà (verrou trainingRunning) : message dédié plutôt
      // qu'une erreur générique — l'utilisateur sait qu'il doit attendre ou annuler l'autre job.
      const conflict = err?.response?.status === 409;
      toast.error(conflict ? t('fineTuning.guardAlreadyRunning') : t('fineTuning.submitError'), {
        description: conflict
          ? t('fineTuning.guardAlreadyRunningDesc')
          : (err?.response?.data?.detail ?? err.message),
      });
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
          <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">{t('fineTuning.kicker')}</p>
          <h2 className="font-headline text-3xl font-bold tracking-tighter">{t('fineTuning.title')}</h2>
        </div>
        <button
          onClick={() => setShowForm(v => !v)}
          className="bg-primary text-on-primary-fixed font-bold py-3 px-6 text-[11px] uppercase tracking-widest hover:opacity-90 transition-opacity flex items-center gap-2"
        >
          <span className="material-symbols-outlined text-sm">{showForm ? 'close' : 'add'}</span>
          {showForm ? t('fineTuning.cancel') : t('fineTuning.newJob')}
        </button>
      </header>

      {/* ── New Job Form ── */}
      {showForm && (
        <section className="bg-surface-container p-8 border-l-4 border-primary animate-in slide-in-from-top-2 duration-300">
          <div className="flex items-center justify-between mb-6">
            <h3 className="font-headline text-lg font-bold uppercase tracking-tight">{t('fineTuning.configure')}</h3>
            <button type="button" onClick={exportRecipe}
              className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant hover:text-on-surface flex items-center gap-1 transition-colors">
              <span className="material-symbols-outlined text-sm">download</span>{t('fineTuning.exportRecipe')}
            </button>
          </div>

          {activeModel && (
            <div className="mb-6 flex flex-wrap items-center gap-2">
              <span className="material-symbols-outlined text-sm text-primary">memory</span>
              <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('fineTuning.activeModel')}</span>
              <span className="text-[11px] font-mono text-primary">{activeModel}</span>
              {suggestedBase && (
                <span className="text-[10px] text-outline">— {t('fineTuning.prefilledHint', { base: suggestedBase })}</span>
              )}
            </div>
          )}

          {recipes.length > 0 && (
            <div className="mb-6 p-4 bg-surface-container-high/50 border border-outline-variant/20">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-3">
                {t('fineTuning.recipes')} {loadingRecipe ? t('fineTuning.recipesLoading') : ''}
              </p>
              <div className="flex flex-wrap gap-2">
                {recipes.map(r => (
                  <button key={r.name} type="button" onClick={() => loadRecipe(r.name)}
                    title={r.description}
                    className="px-3 py-1.5 border border-outline-variant/40 font-label text-[11px] uppercase tracking-widest
                               hover:border-primary hover:text-primary transition-colors">
                    {r.displayName}
                  </button>
                ))}
              </div>
            </div>
          )}

          <form onSubmit={handleSubmit(onFormSubmit)} className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">

            <div className="space-y-2">
              <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('fineTuning.modelName')}</label>
              <input
                type="text" {...register('modelName')}
                className={`w-full bg-surface-container-lowest border ${errors.modelName ? 'border-error' : 'border-outline-variant/30'} px-4 py-2.5 text-sm font-label focus:outline-none focus:border-primary transition-colors`}
                placeholder="spectra-domain"
              />
              {errors.modelName && <p className="text-xs text-error">{errors.modelName.message}</p>}
            </div>

            <div className="space-y-2">
              <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('fineTuning.baseModel')}</label>
              {/* Datalist plutôt que <select> : les alias du catalogue (base_models.json)
                  sont proposés avec leur description, tout en gardant la saisie libre
                  d'un repo HuggingFace complet (« org/nom »), accepté par le backend. */}
              <input
                type="text" {...register('baseModel')} list="base-model-catalog"
                className={`w-full bg-surface-container-lowest border ${errors.baseModel ? 'border-error' : 'border-outline-variant/30'} px-4 py-2.5 text-sm font-label focus:outline-none focus:border-primary transition-colors`}
                placeholder="phi3"
              />
              <datalist id="base-model-catalog">
                {baseCatalog.map(c => (
                  <option key={c.alias} value={c.alias}>
                    {c.description ?? c.hfRepo ?? ''}
                  </option>
                ))}
              </datalist>
              {baseCatalog.length > 0 && (
                <p className="text-[10px] text-outline">{t('fineTuning.baseCatalogHint', { aliases: baseCatalog.map(c => c.alias).join(', ') })}</p>
              )}
              {errors.baseModel && <p className="text-[10px] text-error uppercase tracking-wider">{errors.baseModel.message}</p>}
            </div>

            <div className="space-y-2">
              <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
                {t('fineTuning.epochs')} — <span className="text-primary font-bold">{formValues.epochs}</span>
              </label>
              <input
                type="range" min={1} max={10} {...register('epochs', { valueAsNumber: true })}
                className="w-full accent-primary mt-3"
              />
            </div>

            <div className="space-y-2">
              <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
                {t('fineTuning.loraRank')} — <span className="text-primary font-bold">{formValues.loraRank}</span>
              </label>
              <input
                type="range" min={8} max={128} step={8} {...register('loraRank', { valueAsNumber: true })}
                className="w-full accent-primary mt-3"
              />
            </div>

            <div className="space-y-2">
              <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
                {t('fineTuning.minConfidence')} — <span className="text-primary font-bold">{(formValues.minConfidence || 0).toFixed(1)}</span>
              </label>
              <input
                type="range" min={0.5} max={1.0} step={0.05} {...register('minConfidence', { valueAsNumber: true })}
                className="w-full accent-primary mt-3"
              />
            </div>

            <div className="space-y-3">
              <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('fineTuning.options')}</label>
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input type="checkbox" {...register('packingEnabled')} className="accent-primary" />
                <span className="font-label text-[11px] uppercase tracking-widest">{t('fineTuning.multipacking')}</span>
                <span className="text-[10px] text-on-surface-variant">{t('fineTuning.multipackingHint')}</span>
              </label>
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input type="checkbox" {...register('dpoEnabled')} className="accent-secondary" />
                <span className="font-label text-[11px] uppercase tracking-widest">{t('fineTuning.dpoAlignment')}</span>
                <span className="text-[10px] text-on-surface-variant">{t('fineTuning.dpoHint')}</span>
              </label>
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input type="checkbox" {...register('exportGguf')} className="accent-primary" />
                <span className="font-label text-[11px] uppercase tracking-widest">{t('fineTuning.exportGguf')}</span>
                <span className="text-[10px] text-on-surface-variant">{t('fineTuning.exportGgufHint')}</span>
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
                {submitting ? t('fineTuning.submitting') : t('fineTuning.launch')}
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
            className={`text-[10px] font-bold uppercase tracking-widest px-2 py-1 border transition-colors ${
              autoScroll ? 'bg-primary/10 border-primary text-primary' : 'border-outline text-outline'
            }`}
          >
            {t('fineTuning.autoScroll', { state: autoScroll ? t('fineTuning.on') : t('fineTuning.off') })}
          </button>
          {activeJob && (activeJob.status !== 'COMPLETED' && activeJob.status !== 'FAILED') && (
            <div className="flex items-center gap-2">
              <div className="w-2 h-2 bg-secondary animate-pulse"></div>
              <span className="text-[11px] font-bold text-secondary uppercase tracking-widest">{t('fineTuning.live')}</span>
            </div>
          )}
        </div>

        {!activeJob ? (
          <div className="py-12 flex flex-col items-center justify-center gap-3">
            <span className="material-symbols-outlined text-4xl text-outline">model_training</span>
            <p className="text-xs text-outline italic text-center">
              {t('fineTuning.noActiveJob1')}<br />{t('fineTuning.noActiveJob2')}
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
                  <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('fineTuning.model')}</p>
                  <h3 className="font-headline text-lg font-bold uppercase">{activeJob.modelName}</h3>
                  <p className="text-[11px] text-on-surface-variant">{t('fineTuning.fromBase', { base: activeJob.baseModel })}</p>
                </div>

                <div>
                  <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('fineTuning.status')}</p>
                  <span className={`font-label text-[11px] font-bold uppercase tracking-widest px-2 py-0.5 border ${jobStatusColor(activeJob.status)}`}>
                    {activeJob.status}
                  </span>
                </div>

                <div>
                  <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('fineTuning.currentStep')}</p>
                  <p className="text-xs font-label">{activeJob.currentStep}</p>
                </div>

                {activeJob.datasetSize > 0 && (
                  <div>
                    <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('fineTuning.dataset')}</p>
                    <p className="font-headline font-bold text-xl">{activeJob.datasetSize} <span className="text-xs font-label text-on-surface-variant">{t('fineTuning.pairs')}</span></p>
                  </div>
                )}

                {epochProgress !== null && (
                  <div className="space-y-2">
                    <div className="flex justify-between items-end">
                      <span className="text-[10px] font-label uppercase text-on-surface-variant">{t('fineTuning.trainingProgress')}</span>
                      <span className="font-headline font-bold text-sm">
                        {epochProgress.toFixed(0)}%
                        {eta !== null && (
                          <span className="ml-2 font-label font-normal text-[10px] text-outline tabular-nums normal-case">
                            {t('taskCenter.etaLeft', { time: formatEta(eta) })}
                          </span>
                        )}
                      </span>
                    </div>
                    <div className="w-full bg-outline-variant/20 h-1.5 relative">
                      <div className="absolute top-0 left-0 h-full bg-secondary transition-all" style={{ width: `${epochProgress}%` }} />
                    </div>
                    <div className="flex justify-between text-[10px] text-outline">
                      <span>{t('fineTuning.epochOf', { current: activeJob.currentEpoch, total: activeJob.totalEpochs })}</span>
                      {activeJob.loss !== null && <span>{t('fineTuning.loss', { value: activeJob.loss.toFixed(4) })}</span>}
                    </div>
                  </div>
                )}

                {activeJob.error && (
                  <div className="p-3 bg-error/10 border border-error/30">
                    <p className="text-xs text-error break-words">{activeJob.error}</p>
                  </div>
                )}

                {activeJob.status === 'COMPLETED' && activeJob.outputPath && (
                  <div className="p-3 bg-primary/5 border border-primary/20">
                    <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('fineTuning.output')}</p>
                    <p className="text-[11px] text-primary break-all">{activeJob.outputPath}</p>
                  </div>
                )}
              </div>

              {/* Right: Loss curve + telemetry */}
              <div className="lg:col-span-3 flex flex-col gap-4">

                {/* Loss chart */}
                {(lossHistory.length > 0 || activeJob.status === 'TRAINING') && (
                  <div className="bg-surface-container-lowest border border-outline-variant/10">
                    <div className="px-4 py-2.5 border-b border-outline-variant/10 flex justify-between items-center">
                      <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant font-bold">{t('fineTuning.trainingLoss')}</span>
                      <span className="text-[10px] text-outline">{t('fineTuning.points', { count: lossHistory.length })}</span>
                    </div>
                    <div className="h-32">
                      <LossChart data={lossHistory} totalEpochs={activeJob.totalEpochs} />
                    </div>
                  </div>
                )}

              {/* Telemetry stream */}
              <div className="h-48 bg-surface-container-lowest font-mono text-[11px] flex flex-col">
                <div className="px-4 py-2.5 bg-surface-container-lowest/80 backdrop-blur-sm border-b border-outline-variant/10 flex justify-between items-center shrink-0">
                  <span className="uppercase tracking-widest text-on-surface-variant font-bold text-[10px]">{t('fineTuning.telemetry')}</span>
                  <div className="flex items-center gap-3">
                    {/* État de la connexion SSE (live / reconnexion / coupée) */}
                    <span
                      className={`flex items-center gap-1 text-[10px] uppercase tracking-widest font-bold ${
                        sseStatus === 'open' ? 'text-primary'
                          : sseStatus === 'connecting' ? 'text-secondary'
                          : 'text-error'
                      }`}
                      title={t('fineTuning.telemetryState', { state: sseStatus })}
                    >
                      <span className={`w-1.5 h-1.5 rounded-full ${
                        sseStatus === 'open' ? 'bg-primary animate-pulse'
                          : sseStatus === 'connecting' ? 'bg-secondary animate-pulse'
                          : 'bg-error'
                      }`} aria-hidden="true" />
                      {sseStatus === 'open' ? t('fineTuning.live') : sseStatus === 'connecting' ? t('fineTuning.connecting') : t('fineTuning.disconnected')}
                    </span>
                    <span className="text-[10px] text-outline">{t('fineTuning.events', { count: logs.length })}</span>
                  </div>
                </div>
                {logs.length === 0 ? (
                  <div className="flex-1 flex items-center justify-center">
                    <p className="text-outline italic text-[11px]">
                      {sseStatus === 'closed'
                        ? t('fineTuning.streamInterrupted')
                        : sseStatus === 'connecting'
                          ? t('fineTuning.streamConnecting')
                          : t('fineTuning.streamWaiting')}
                    </p>
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
              </div>{/* end flex-col right column */}
            </div>
          </div>
        )}
      </section>

      {/* ── Training History ── */}
      <section className="space-y-6">
        <div className="flex items-center justify-between">
          <h3 className="font-headline text-xl font-bold tracking-tight uppercase">{t('fineTuning.history')}</h3>
          <button
            onClick={loadJobs}
            className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant hover:text-on-surface transition-colors flex items-center gap-1"
          >
            <span className="material-symbols-outlined text-sm">refresh</span>{t('fineTuning.refresh')}
          </button>
        </div>

        <div className="bg-surface-container overflow-x-auto">
          <table className="w-full min-w-[640px] text-left border-collapse">
            <thead>
              <tr className="bg-surface-container-high">
                {['colJobId', 'colModel', 'colBase', 'colDataset', 'colEpochs', 'colStatus', 'colDate'].map(h => (
                  <th key={h} className="px-5 py-3 font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t(`fineTuning.${h}`)}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-outline-variant/10">
              {jobs.length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-5 py-8 text-center text-xs text-outline italic">
                    {t('fineTuning.noJobs')}
                  </td>
                </tr>
              ) : jobs.map(job => (
                <tr
                  key={job.jobId}
                  onClick={() => setActiveJob(job)}
                  // Ligne focalisable au clavier : Entrée/Espace charge le job dans le moniteur.
                  tabIndex={0}
                  onKeyDown={e => {
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setActiveJob(job); }
                  }}
                  className="hover:bg-surface-container-highest transition-colors cursor-pointer focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary focus-visible:-outline-offset-2"
                >
                  <td className="px-5 py-3">
                    <span className="font-headline font-medium text-xs">{job.jobId.slice(0, 8).toUpperCase()}</span>
                  </td>
                  <td className="px-5 py-3 font-label text-xs uppercase">{job.modelName}</td>
                  <td className="px-5 py-3 font-label text-xs uppercase text-on-surface-variant">{job.baseModel}</td>
                  <td className="px-5 py-3 font-label text-xs">{t('fineTuning.pairsCount', { count: job.datasetSize })}</td>
                  <td className="px-5 py-3 font-label text-xs">{job.parameters?.epochs ?? '—'}</td>
                  <td className="px-5 py-3">
                    <span className={`text-[11px] font-bold uppercase tracking-widest ${
                      job.status === 'COMPLETED' ? 'text-primary' :
                      job.status === 'FAILED'    ? 'text-error' :
                                                   'text-secondary'
                    }`}>{job.status}</span>
                  </td>
                  <td className="px-5 py-3 font-label text-xs text-on-surface-variant">
                    {job.createdAt ? new Date(job.createdAt).toLocaleDateString(i18n.language) : '—'}
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
