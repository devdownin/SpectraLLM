import { useContextSearchParams } from '../hooks/useContextSearchParams';
import { useViewState } from '../hooks/useViewState';
import { useEffect, useMemo, useRef, useState } from 'react';
import type { FC } from 'react';
import type { TFunction } from 'i18next';
import { Trans, useTranslation } from 'react-i18next';
import { useMutation, useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ablationApi, fineTuningApi } from '../services/api';
import { etaMs, formatEta } from '../hooks/useGlobalTasks';
import AblationCharts from '../components/charts/AblationCharts';
import { PageHeader, Button, SpotlightCard, StatusBadge, DetailPanel, WorkList, WorkListRow, ListFeedback, ListTools } from '../components/ui';
import type {
  AblationArmConfig,
  AblationArmReport,
  AblationJobDto,
  AblationReport,
  RagOverrides,
} from '../types/api';
import { apiErrorMessage, apiErrorStatus } from '../lib/apiError';

/**
 * Écran « Optimisation des réponses » — valide et compare l'apport de chaque option
 * d'apprentissage (fine-tuning) et d'optimisation RAG via une ablation A/B sur le benchmark
 * tenu à l'écart. Chaque bras n'active qu'un changement de plus → le delta = gain marginal.
 */

// ── Métadonnées pédagogiques des modules d'optimisation ───────────────────────

type ModuleKey = keyof RagOverrides;

const MODULE_KEYS: ModuleKey[] = [
  'hybrid', 'rerank', 'multiQuery', 'corrective',
  'compression', 'selfRag', 'adaptive', 'conversational',
];

const moduleName = (t: TFunction, key: string): string =>
  t(`optimization.modules.${key}.name`, key);

// ── Métriques affichées (avec sens d'amélioration) ────────────────────────────

interface MetricDef {
  key: string;
  higherIsBetter: boolean;
  format: (arm: AblationArmReport) => string;
  value: (arm: AblationArmReport) => number;
  pct?: boolean;      // delta exprimé en points de %
  stdKey?: string;    // clé dans arm.stdDev pour l'écart-type
  retrieval?: boolean; // métrique de retrieval (delta masqué si non évaluée)
}

const METRICS: MetricDef[] = [
  { key: 'avgScore', stdKey: 'avgScore', higherIsBetter: true, value: a => a.quality.avgScore, format: a => a.quality.avgScore.toFixed(2) },
  { key: 'halluc', stdKey: 'hallucinationRate', higherIsBetter: false, pct: true, value: a => a.quality.hallucinationRate, format: a => `${(a.quality.hallucinationRate * 100).toFixed(0)}%` },
  { key: 'refusal', stdKey: 'refusalAccuracy', higherIsBetter: true, pct: true, value: a => a.quality.refusalAccuracy, format: a => `${(a.quality.refusalAccuracy * 100).toFixed(0)}%` },
  { key: 'hit', stdKey: 'hitRate', retrieval: true, higherIsBetter: true, value: a => a.retrieval.hitRate, format: a => a.retrieval.evaluatedQuestions ? a.retrieval.hitRate.toFixed(2) : '—' },
  { key: 'mrr', stdKey: 'mrr', retrieval: true, higherIsBetter: true, value: a => a.retrieval.mrr, format: a => a.retrieval.evaluatedQuestions ? a.retrieval.mrr.toFixed(2) : '—' },
  { key: 'recall', stdKey: 'recallAtK', retrieval: true, higherIsBetter: true, value: a => a.retrieval.recallAtK, format: a => a.retrieval.evaluatedQuestions ? a.retrieval.recallAtK.toFixed(2) : '—' },
  { key: 'tokens', stdKey: 'avgContextTokens', higherIsBetter: false, value: a => a.avgContextTokens, format: a => Math.round(a.avgContextTokens).toString() },
  { key: 'p50', stdKey: 'p50LatencyMs', higherIsBetter: false, value: a => a.p50LatencyMs, format: a => `${Math.round(a.p50LatencyMs)}ms` },
];

// ── Statistiques d'affichage ──────────────────────────────────────────────────

/** Écart-type de la métrique pour un bras (0 si indisponible). */
function metricStd(metric: MetricDef, arm: AblationArmReport): number {
  return (metric.stdKey && arm.stdDev?.[metric.stdKey]) || 0;
}

/** ±σ formaté pour affichage sous la valeur (unité cohérente avec la métrique). */
function fmtStd(metric: MetricDef, arm: AblationArmReport): string {
  const s = metricStd(metric, arm);
  if (s <= 1e-9) return '';
  if (metric.pct) return `±${(s * 100).toFixed(1)}`;
  if (metric.key === 'p50' || metric.key === 'tokens') return `±${Math.round(s)}`;
  return `±${s.toFixed(2)}`;
}

/**
 * Un delta est « significatif » s'il dépasse le bruit combiné des deux bras
 * (√(σ_arm² + σ_base²)). Avec une seule répétition (σ=0) on ne peut pas trancher → on
 * considère le delta comme affichable normalement (non grisé).
 */
function isSignificant(metric: MetricDef, base: AblationArmReport, arm: AblationArmReport): boolean {
  const combined = Math.hypot(metricStd(metric, base), metricStd(metric, arm));
  if (combined <= 1e-9) return true;
  return Math.abs(metric.value(arm) - metric.value(base)) > combined;
}

// ── Construction des bras (presets) ───────────────────────────────────────────

const ALL_ON: RagOverrides = {
  hybrid: true, rerank: true, multiQuery: true, corrective: true,
  compression: true, selfRag: true, adaptive: true, conversational: true,
};
const ALL_OFF: RagOverrides = {
  hybrid: false, rerank: false, multiQuery: false, corrective: false,
  compression: false, selfRag: false, adaptive: false, conversational: false,
};

function ragGainArms(t: TFunction): AblationArmConfig[] {
  return [
    { label: t('optimization.armLlmOnly'), useRag: false, overrides: null },
    { label: t('optimization.armRagDeploy'), useRag: true, overrides: null },
  ];
}

/** Ablation cumulative : on part du RAG vectoriel nu, puis on ajoute un module à la fois. */
function cumulativeArms(t: TFunction): AblationArmConfig[] {
  const order: ModuleKey[] = ['hybrid', 'rerank', 'multiQuery', 'corrective', 'compression', 'selfRag'];
  const arms: AblationArmConfig[] = [
    { label: t('optimization.armRagBare'), useRag: true, overrides: { ...ALL_OFF } },
  ];
  const acc: RagOverrides = { ...ALL_OFF };
  for (const k of order) {
    acc[k] = true;
    arms.push({ label: `+ ${moduleName(t, k)}`, useRag: true, overrides: { ...acc } });
  }
  return arms;
}

/** Leave-one-out : tout activé, puis on retire un module à la fois (apport isolé de chacun). */
function leaveOneOutArms(t: TFunction): AblationArmConfig[] {
  const arms: AblationArmConfig[] = [
    { label: t('optimization.armAllOn'), useRag: true, overrides: { ...ALL_ON } },
  ];
  for (const key of MODULE_KEYS) {
    arms.push({ label: `− ${moduleName(t, key)}`, useRag: true, overrides: { ...ALL_ON, [key]: false } });
  }
  return arms;
}

type PresetKey = 'rag' | 'cumulative' | 'loo' | 'finetuning';

// ── UI helpers ────────────────────────────────────────────────────────────────

function deltaColor(delta: number, higherIsBetter: boolean): string {
  if (Math.abs(delta) < 1e-9) return 'text-on-surface-variant';
  const good = higherIsBetter ? delta > 0 : delta < 0;
  return good ? 'text-primary' : 'text-error';
}

function fmtDelta(metric: MetricDef, base: AblationArmReport, arm: AblationArmReport): string {
  if (metric.retrieval && (!base.retrieval.evaluatedQuestions || !arm.retrieval.evaluatedQuestions)) return '';
  const d = metric.value(arm) - metric.value(base);
  if (Math.abs(d) < 1e-9) return '±0';
  const sign = d > 0 ? '+' : '';
  if (metric.pct) return `${sign}${(d * 100).toFixed(0)} pts`;
  if (metric.key === 'p50') return `${sign}${Math.round(d)}ms`;
  if (metric.key === 'tokens') return `${sign}${Math.round(d)}`;
  return `${sign}${d.toFixed(2)}`;
}

// ── Export CSV ────────────────────────────────────────────────────────────────

/** Échappe un champ CSV (séparateur virgule, décimales point — lisible tableur). */
function csvCell(v: string | number): string {
  const s = String(v);
  return /[",\n;]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function buildCsv(report: AblationReport, t: TFunction): string {
  const headers = [
    'bras', 'modele', 'rag', 'runs',
    'exactitude_sur_10', 'exactitude_std', 'hallucination_taux', 'abstention_juste_taux',
    'hit_at_k', 'mrr', 'recall_at_k', 'retrieval_questions_evaluees',
    'tokens_contexte', 'latence_p50_ms', 'latence_moyenne_ms', 'modules_declenches',
  ];
  const num = (n: number, d = 4) => Number(n.toFixed(d));
  const rows = report.arms.map(a => {
    const hasRetrieval = a.retrieval.evaluatedQuestions > 0;
    const modules = Object.entries(a.appliedCounts || {})
      .map(([k, n]) => `${moduleName(t, k)} x${n}`).join('; ');
    return [
      a.label, a.model, a.useRag ? 'oui' : 'non', a.runs ?? 1,
      num(a.quality.avgScore, 2), num(a.stdDev?.avgScore ?? 0, 3),
      num(a.quality.hallucinationRate), num(a.quality.refusalAccuracy),
      hasRetrieval ? num(a.retrieval.hitRate) : '',
      hasRetrieval ? num(a.retrieval.mrr) : '',
      hasRetrieval ? num(a.retrieval.recallAtK) : '',
      a.retrieval.evaluatedQuestions,
      Math.round(a.avgContextTokens), Math.round(a.p50LatencyMs), Math.round(a.avgLatencyMs), modules,
    ].map(csvCell).join(',');
  });
  // BOM pour qu'Excel lise l'UTF-8 (accents) correctement.
  return '﻿' + [headers.join(','), ...rows].join('\r\n') + '\r\n';
}

function downloadCsv(report: AblationReport, t: TFunction): void {
  const blob = new Blob([buildCsv(report, t)], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
  a.href = url;
  a.download = `ablation-${stamp}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

const Optimization: FC = () => {
  const { t, i18n } = useTranslation();
  const [preset, setPreset] = useState<PresetKey>('rag');
  const [models, setModels] = useState<string[]>([]);
  const [baseModel, setBaseModel] = useState<string>('');
  const [tunedModel, setTunedModel] = useState<string>('');
  const [maxChunks, setMaxChunks] = useState<number>(5);
  const [runs, setRuns] = useState<number>(1);
  const [params, setParams] = useContextSearchParams();
  const targetJob = params.get('job');
  const [reportOpen, setReportOpen] = useState(Boolean(targetJob));
  const [jobId, setJobId] = useState<string | null>(targetJob);

  const [historySearch, setHistorySearch] = useViewState('ablation.search', '');
  const [historyStatus, setHistoryStatus] = useViewState('ablation.status', 'all');
  const [historySort, setHistorySort] = useViewState('ablation.sort', 'recent');
  const history = useQuery<AblationJobDto[]>({ queryKey: ['ablation-history'], queryFn: async () => (await ablationApi.listJobs()).data ?? [], refetchInterval: q => q.state.data?.some(j => ['PENDING', 'RUNNING'].includes(j.status)) ? 5000 : false });
  useEffect(() => {
    if (targetJob) { setJobId(targetJob); setReportOpen(true); }
    else if (!jobId && history.data?.[0]?.jobId) setJobId(history.data[0].jobId);
  }, [targetJob, jobId, history.data]);
  const openHistoryJob = (id: string) => { setJobId(id); setReportOpen(true); const next = new URLSearchParams(params); next.set('job', id); setParams(next); };
  const visibleJobs = (history.data ?? []).filter(j => (historyStatus === 'all' || j.status === historyStatus) && `${j.label} ${j.jobId}`.toLowerCase().includes(historySearch.toLowerCase())).sort((a, b) => historySort === 'name' ? (a.label ?? a.jobId).localeCompare(b.label ?? b.jobId) : b.createdAt.localeCompare(a.createdAt));

  useEffect(() => {
    fineTuningApi.getModels()
      .then(res => {
        const raw = res.data ?? [];
        const names: string[] = Array.isArray(raw)
          ? raw.map((m: unknown) => (typeof m === 'string' ? m : (m as { name?: string })?.name)).filter(Boolean) as string[]
          : [];
        setModels(names);
        if (names.length) { setBaseModel(names[0]); setTunedModel(names[names.length - 1]); }
      })
      .catch(() => { /* liste de modèles optionnelle */ });
  }, []);

  const arms: AblationArmConfig[] = useMemo(() => {
    switch (preset) {
      case 'cumulative': return cumulativeArms(t);
      case 'loo':        return leaveOneOutArms(t);
      case 'finetuning': return [
        { label: t('optimization.armBaseline', { model: baseModel || t('optimization.armModelFallback1') }), model: baseModel || null, useRag: true, overrides: null },
        { label: t('optimization.armTuned', { model: tunedModel || t('optimization.armModelFallback2') }), model: tunedModel || null, useRag: true, overrides: null },
      ];
      case 'rag':
      default:           return ragGainArms(t);
    }
  }, [preset, baseModel, tunedModel, t]);

  // Suivi du job asynchrone : polling 2 s tant qu'il tourne, arrêt une fois terminal.
  const { data: job, isError: jobUnavailable } = useQuery<AblationJobDto>({
    queryKey: ['ablation-job', jobId],
    queryFn: async () => (await ablationApi.getJob(jobId!)).data,
    enabled: !!jobId,
    refetchInterval: (query) => {
      const s = query.state.data?.status;
      return s === 'PENDING' || s === 'RUNNING' ? 2000 : false;
    },
  });

  const submit = useMutation({
    mutationFn: async () =>
      (await ablationApi.runAsync({ arms, maxContextChunks: maxChunks, runs })).data as { jobId: string },
    onSuccess: (data) => { openHistoryJob(data.jobId); void history.refetch(); },
    onError: (error) => {
      const conflict = apiErrorStatus(error) === 409;
      toast.error(conflict ? t('optimization.alreadyRunning') : t('optimization.startFailed'), {
        description: conflict ? undefined
          : apiErrorMessage(error, t('optimization.startFailedHint')),
      });
    },
  });

  const cancel = useMutation({
    mutationFn: () => ablationApi.cancelJob(jobId!),
    onSuccess: () => toast.info(t('optimization.cancelRequested')),
    onError: () => toast.error(t('optimization.cancelFailed')),
  });

  const running = submit.isPending || job?.status === 'PENDING' || job?.status === 'RUNNING';
  const report: AblationReport | null = job?.status === 'COMPLETED' ? (job.report ?? null) : null;

  // Toast de fin sur transition observée en session (pas au chargement d'un vieux rapport).
  const prevStatusRef = useRef<AblationJobDto['status'] | null>(null);
  useEffect(() => {
    const prev = prevStatusRef.current;
    prevStatusRef.current = job?.status ?? null;
    if (!job || prev === null || prev === job.status) return;
    if (job.status === 'COMPLETED') { setReportOpen(true); toast.success(t('optimization.done')); }
    else if (job.status === 'FAILED') {
      toast.error(t('optimization.failed'), { description: job.error ?? undefined });
    } else if (job.status === 'CANCELLED') toast.info(t('optimization.cancelledToast'));
  }, [job, t]);

  // Temps restant estimé (extrapolation linéaire, comme le TaskCenter).
  const eta = job && job.status === 'RUNNING' && job.totalUnits > 0
    ? etaMs({
        status: 'running',
        progress: Math.min(1, job.processedUnits / job.totalUnits),
        startedAt: job.createdAt,
      }, Date.now())
    : null;

  const baseArm = report?.arms?.[0];
  const noRetrieval = report?.arms?.every(a => !a.retrieval.evaluatedQuestions);

  return (
    <div className="space-y-6 animate-in fade-in duration-700">
      {/* Header */}
      {targetJob && jobUnavailable && <p role="alert">{t('detail.missingTarget')}</p>}
      <PageHeader
        kicker={t('optimization.kicker')}
        title={t('optimization.title')}
        description={
          <Trans i18nKey="optimization.intro">
            Measure and <strong className="text-on-surface">validate the real contribution</strong> of each learning
            option (fine-tuning) and optimization option (RAG modules). Each <em>arm</em> changes only one thing at a
            time: the <strong className="text-on-surface">delta</strong> between two arms is the marginal gain of that
            option — always to be read against its latency cost.
          </Trans>
        }
      />

      {/* Explications des options */}
      <section className="space-y-3">
        <h3 className="font-headline text-xs uppercase tracking-widest text-on-surface-variant">{t('optimization.modulesTitle')}</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-3">
          {MODULE_KEYS.map(key => (
            <SpotlightCard key={key} className="bg-surface-container-low border border-outline-variant/10 p-4">
              <p className="font-headline text-sm font-bold mb-1">{t(`optimization.modules.${key}.name`)}</p>
              <p className="text-[11px] text-on-surface-variant leading-relaxed mb-2">{t(`optimization.modules.${key}.what`)}</p>
              <p className="text-[11px] text-primary leading-snug">{t('optimization.gainPrefix')} {t(`optimization.modules.${key}.gain`)}</p>
              <p className="text-[11px] text-on-surface-variant leading-snug mt-0.5">{t('optimization.costPrefix')} {t(`optimization.modules.${key}.cost`)}</p>
            </SpotlightCard>
          ))}
        </div>
        <p className="text-[11px] text-on-surface-variant">
          <Trans i18nKey="optimization.modulesFootnote">
            On top comes the <strong className="text-on-surface">learning</strong> axis: base model vs fine-tuned
            model (SFT/DPO), compared via the dedicated preset.
          </Trans>
        </p>
      </section>

      {/* Configuration du passage */}
      <section className="bg-surface-container-low border border-outline-variant/10 p-5 space-y-4">
        <h3 className="font-headline text-xs uppercase tracking-widest text-on-surface-variant">{t('optimization.protocol')}</h3>
        <div className="flex flex-wrap gap-2">
          {(['rag', 'cumulative', 'loo', 'finetuning'] as PresetKey[]).map((k) => (
            <button
              key={k}
              onClick={() => setPreset(k)}
              className={`px-3 py-2 text-left border transition-colors ${preset === k
                ? 'border-primary bg-primary/10 text-on-surface'
                : 'border-outline-variant/20 text-on-surface-variant hover:border-outline-variant/40'}`}
            >
              <span className="block font-headline text-xs font-bold">{t(`optimization.presets.${k}.label`)}</span>
              <span className="block text-[11px] text-on-surface-variant">{t(`optimization.presets.${k}.sub`)}</span>
            </button>
          ))}
        </div>

        {preset === 'finetuning' && (
          <div className="flex flex-wrap gap-4">
            <label className="text-xs text-on-surface-variant">
              {t('optimization.baseModelLabel')}
              <select value={baseModel} onChange={e => setBaseModel(e.target.value)}
                className="block mt-1 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs">
                {models.length === 0 && <option value="">{t('optimization.activeModelOption')}</option>}
                {models.map(m => <option key={m} value={m}>{m}</option>)}
              </select>
            </label>
            <label className="text-xs text-on-surface-variant">
              {t('optimization.tunedModelLabel')}
              <select value={tunedModel} onChange={e => setTunedModel(e.target.value)}
                className="block mt-1 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs">
                {models.length === 0 && <option value="">{t('optimization.activeModelOption')}</option>}
                {models.map(m => <option key={m} value={m}>{m}</option>)}
              </select>
            </label>
          </div>
        )}

        {/* Aperçu des bras */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('optimization.armsLabel')}</span>
          {arms.map((a, i) => (
            <span key={i} className="px-2 py-1 bg-surface-container border border-outline-variant/20 text-[11px] text-on-surface">
              {a.label}
            </span>
          ))}
        </div>

        <div className="flex items-center gap-4 flex-wrap">
          <label className="text-xs text-on-surface-variant flex items-center gap-2">
            {t('optimization.topK')}
            <input type="number" min={1} max={20} value={maxChunks}
              onChange={e => setMaxChunks(Math.max(1, Math.min(20, Number(e.target.value) || 5)))}
              className="w-16 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs" />
          </label>
          <label className="text-xs text-on-surface-variant flex items-center gap-2" title={t('optimization.runsHint')}>
            {t('optimization.runsLabel')}
            <input type="number" min={1} max={10} value={runs}
              onChange={e => setRuns(Math.max(1, Math.min(10, Number(e.target.value) || 1)))}
              className="w-16 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs" />
          </label>
          <Button onClick={() => submit.mutate()} disabled={running} icon={running ? undefined : 'play_arrow'} loading={running}>
            {running ? t('optimization.launching') : t('optimization.launch')}
          </Button>
          <span className="text-[11px] text-on-surface-variant">
            {t('optimization.slowHint', { arms: arms.length, runs })}
          </span>
        </div>
      </section>

      {/* Suivi du passage en cours : progression réelle + jalon + ETA + annulation. */}
      {running && (
        <section className="bg-surface-container-low border border-outline-variant/10 p-5 space-y-3">
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-2 min-w-0">
              <span className="material-symbols-outlined text-sm text-secondary animate-spin">progress_activity</span>
              <span className="text-xs text-on-surface truncate">
                {job?.currentStep ?? t('optimization.starting')}
              </span>
            </div>
            <button
              onClick={() => cancel.mutate()}
              disabled={cancel.isPending || !jobId}
              className="shrink-0 px-3 py-1.5 border border-error/40 text-error font-headline text-[11px] uppercase tracking-widest hover:bg-error/10 transition-colors disabled:opacity-40"
            >
              {t('optimization.cancelRun')}
            </button>
          </div>
          <div className="relative w-full bg-outline-variant/20 h-1.5 overflow-hidden">
            {job && job.totalUnits > 0 ? (
              <div
                className="absolute top-0 left-0 h-full bg-secondary transition-all duration-500"
                style={{ width: `${Math.round((job.processedUnits / job.totalUnits) * 100)}%` }}
              />
            ) : null}
            <div className="scan-beam" />
          </div>
          <div className="flex items-center justify-between text-[11px] text-on-surface-variant tabular-nums">
            <span>{job ? t('optimization.progressUnits', { processed: job.processedUnits, total: job.totalUnits }) : '…'}</span>
            {eta !== null && <span>{t('optimization.etaLeft', { time: formatEta(eta) })}</span>}
          </div>
        </section>
      )}

      {job && <StatusBadge status={job.status} />}

      {/* Passage interrompu : cause affichée plutôt qu'un écran qui redevient muet. */}
      {!running && job?.status === 'FAILED' && (
        <p className="text-xs text-error bg-error/10 border border-error/30 px-3 py-2">
          {t('optimization.failedBanner', { error: job.error ?? t('optimization.unknownError') })}
        </p>
      )}
      {!running && job?.status === 'CANCELLED' && (
        <p className="text-xs text-on-surface-variant bg-surface-container-low border border-outline-variant/20 px-3 py-2">
          {t('optimization.cancelledBanner', { processed: job.processedUnits, total: job.totalUnits })}
        </p>
      )}

      <section className="space-y-4">
        <h2 className="font-headline text-xl font-bold">{t('workList.history')}</h2>
        <ListTools search={historySearch} onSearch={setHistorySearch} status={historyStatus} onStatus={setHistoryStatus} statuses={['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']} sort={historySort} onSort={setHistorySort} onReset={() => { setHistorySearch(''); setHistoryStatus('all'); setHistorySort('recent'); }} />
        <ListFeedback loading={history.isLoading} error={history.isError} empty={!visibleJobs.length} retry={() => { void history.refetch(); }} action={<Button variant="outline" onClick={() => { setHistorySearch(''); setHistoryStatus('all'); }}>{t('workList.reset')}</Button>}>
          {!!visibleJobs.length && <WorkList viewKey="ablation" filters={JSON.stringify([historySearch, historyStatus, historySort])} label={t('workList.history')}>{visibleJobs.map(j => <WorkListRow key={j.jobId} selected={jobId === j.jobId}
            identity={<><button className="font-medium text-left hover:text-primary" onClick={() => openHistoryJob(j.jobId)}>{j.label ?? j.jobId}</button><p className="text-xs text-on-surface-variant break-all">{j.jobId}</p></>}
            status={<StatusBadge status={j.status} />}
            context={<div className="text-xs"><p>{j.currentStep ?? '—'}</p><p>{j.processedUnits}/{j.totalUnits}</p>{j.error && <p className="text-error break-all">{j.error}</p>}</div>}
            updated={<time dateTime={j.completedAt ?? j.createdAt}>{new Date(j.completedAt ?? j.createdAt).toLocaleString(i18n.language)}</time>}
            actions={<Button size="sm" variant="outline" onClick={() => openHistoryJob(j.jobId)}>{t(['PENDING', 'RUNNING'].includes(j.status) ? 'workList.follow' : j.status === 'COMPLETED' ? 'workList.result' : j.status === 'FAILED' ? 'workList.inspect' : 'workList.open')}</Button>}
          />)}</WorkList>}
        </ListFeedback>
      </section>

      {report && baseArm && !running && <Button variant="outline" onClick={() => setReportOpen(true)} icon="analytics">{t('activity.result')}</Button>}
      {report && baseArm && !running && reportOpen && <DetailPanel
        title={t('optimization.resultsTitle', { count: report.benchmarkSize, base: baseArm.label })}
        onClose={() => setReportOpen(false)}
        summary={<div className="flex flex-wrap gap-3"><StatusBadge status={job?.status} /><span className="text-sm break-all">{job?.jobId}</span></div>}
        history={<dl className="text-sm space-y-2"><dt>{t('detail.started')}</dt><dd>{new Date(report.startedAt).toLocaleString(i18n.language)}</dd><dt>{t('detail.finished')}</dt><dd>{new Date(report.completedAt).toLocaleString(i18n.language)}</dd></dl>}
        actions={<div className="flex flex-wrap gap-2"><Button variant="outline" onClick={() => downloadCsv(report, t)} icon="download">{t('optimization.exportCsv')}</Button><Button variant="outline" onClick={() => setReportOpen(false)}>{t('detail.close')}</Button></div>}
        details={<section className="space-y-4">
          {noRetrieval && (
            <p className="text-[11px] text-secondary bg-secondary/10 border border-secondary/20 px-3 py-2">
              <Trans i18nKey="optimization.noRetrieval">
                Retrieval metrics (Hit@k/MRR/Recall) not computed: no benchmark question is annotated
                with the <code>expectedSources</code> field. Add it to measure retrieval quality.
              </Trans>
            </p>
          )}

          <div className="overflow-x-auto border border-outline-variant/10">
            <table className="w-full text-xs">
              <thead>
                <tr className="bg-surface-container-low text-on-surface-variant">
                  <th className="text-left font-label text-[11px] uppercase tracking-widest px-3 py-2">{t('optimization.armHeader')}</th>
                  {METRICS.map(m => (
                    <th key={m.key} className="text-right font-label text-[11px] uppercase tracking-widest px-3 py-2 relative group">
                      <span className="cursor-help border-b border-dotted border-on-surface-variant/50">{t(`optimization.metrics.${m.key}.label`)}</span>
                      <div className="absolute bottom-full right-0 mb-2 hidden group-hover:block w-48 p-2 bg-surface-container-high border border-outline-variant/30 shadow-lg text-[11px] text-on-surface normal-case tracking-normal rounded z-10 font-sans text-left">
                        {t(`optimization.metrics.${m.key}.help`)}
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {report.arms.map((arm, i) => (
                  <tr key={i} className="border-t border-outline-variant/10 hover:bg-surface-container-high/30">
                    <td className="px-3 py-2">
                      <span className="text-on-surface font-medium">{arm.label}</span>
                      <span className="block text-[11px] text-on-surface-variant">
                        {arm.model}{arm.useRag ? '' : ` ${t('optimization.noRag')}`}
                      </span>
                    </td>
                    {METRICS.map(m => {
                      const std = fmtStd(m, arm);
                      const sig = isSignificant(m, baseArm, arm);
                      return (
                        <td key={m.key} className="px-3 py-2 text-right tabular-nums">
                          <span className="text-on-surface">{m.format(arm)}</span>
                          {std && <span className="text-[10px] text-on-surface-variant ml-1">{std}</span>}
                          {i > 0 && (
                            <span
                              className={`block text-[11px] ${sig ? deltaColor(m.value(arm) - m.value(baseArm), m.higherIsBetter) : 'text-on-surface-variant/50'}`}
                              title={sig ? '' : t('optimization.notSignificant')}
                            >
                              {!sig && fmtDelta(m, baseArm, arm) ? '≈ ' : ''}{fmtDelta(m, baseArm, arm)}
                            </span>
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {report.arms.some(a => a.runs > 1)
            ? <p className="text-[11px] text-on-surface-variant">
                {t('optimization.multiRunNote', { runs: report.arms[0]?.runs })}
              </p>
            : <p className="text-[11px] text-on-surface-variant">
                {t('optimization.singleRunNote')}
              </p>}

          {/* Graphiques */}
          <AblationCharts arms={report.arms} />

          {/* Modules effectivement déclenchés (validation) */}
          <div className="space-y-2">
            <h4 className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
              {t('optimization.appliedTitle')}
            </h4>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
              {report.arms.map((arm, i) => {
                const fired = Object.entries(arm.appliedCounts || {});
                return (
                  <div key={i} className="bg-surface-container-low border border-outline-variant/10 p-3">
                    <p className="text-[11px] text-on-surface font-medium mb-1">{arm.label}</p>
                    {fired.length === 0 ? (
                      <p className="text-[11px] text-on-surface-variant">{t('optimization.appliedNone')}</p>
                    ) : (
                      <div className="flex flex-wrap gap-1">
                        {fired.map(([k, n]) => (
                          <span key={k} className="px-1.5 py-0.5 bg-primary/10 text-primary text-[11px] border border-primary/20">
                            {moduleName(t, k)} ×{n}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          {/* Légende des métriques */}
          <details className="bg-surface-container-low border border-outline-variant/10 p-4">
            <summary className="cursor-pointer font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
              {t('optimization.legendTitle')}
            </summary>
            <ul className="mt-3 space-y-1.5">
              {METRICS.map(m => (
                <li key={m.key} className="text-[11px] text-on-surface-variant">
                  <strong className="text-on-surface">{t(`optimization.metrics.${m.key}.label`)}</strong> — {t(`optimization.metrics.${m.key}.help`)}{' '}
                  <span className="text-on-surface-variant/70">
                    ({m.higherIsBetter ? t('optimization.higherBetter') : t('optimization.lowerBetter')})
                  </span>
                </li>
              ))}
              <li className="text-[11px] text-on-surface-variant pt-1">
                <Trans i18nKey="optimization.legendDelta">
                  Deltas in <span className="text-primary">green</span> indicate an improvement,
                  in <span className="text-error">red</span> a degradation, relative to the first arm.
                </Trans>
              </li>
            </ul>
          </details>
        </section>} />}

    </div>
  );
};

export default Optimization;
