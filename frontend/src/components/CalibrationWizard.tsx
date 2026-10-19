import { useEffect, useState } from 'react';
import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ablationApi, qualityBenchmarkApi } from '../services/api';
import { Button } from './ui';
import type { BenchmarkDraftJob, BenchmarkEntry, BenchmarkInfo } from '../types/api';
import { apiErrorMessage, apiErrorStatus } from '../lib/apiError';
import {
  DEDUP_STRATEGIES, DEFAULT_GRID, MAX_ARMS, NEIGHBOR_WINDOWS, RERANK_SCORES, UNANSWERABLE_TARGET,
  armCount, draftToEntries, invalidEntries, outOfCorpusEntry, toCalibrationBody, unanswerableShare,
} from '../lib/calibration';
import type { GridState } from '../lib/calibration';

const DRAFT_DEFAULT_SIZE = 30;
const DRAFT_MAX_SIZE = 60;

const isRunning = (job?: BenchmarkDraftJob) => job?.status === 'PENDING' || job?.status === 'RUNNING';

const toggled = <T,>(values: T[], value: T): T[] =>
  values.includes(value) ? values.filter(v => v !== value) : [...values, value];

/** Pastille à bascule (valeur de la grille). */
const Chip: FC<{ pressed: boolean; onClick: () => void; children: string }> = ({ pressed, onClick, children }) => (
  <button
    type="button"
    aria-pressed={pressed}
    onClick={onClick}
    className={`border px-2.5 py-1 text-xs tabular-nums ${pressed ? 'border-primary bg-primary/10 text-primary font-bold' : 'border-outline-variant/30 text-on-surface-variant hover:border-primary/50'}`}
  >{children}</button>
);

/**
 * Calibration des réglages RAG sur le corpus, en trois temps : un jeu d'évaluation tiré de VOS
 * documents (brouillon à relire), la relecture, puis la grille de réglages à mesurer.
 *
 * Le benchmark embarqué ne parle pas de vos documents : calibrer dessus ne dirait rien de votre
 * corpus. L'écran le signale tant qu'aucun jeu relu n'est enregistré.
 */
const CalibrationWizard: FC<{ onLaunched: (jobId: string) => void }> = ({ onLaunched }) => {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  const benchmark = useQuery<BenchmarkInfo>({
    queryKey: ['calibration-benchmark'],
    queryFn: async () => (await qualityBenchmarkApi.getBenchmark()).data,
  });
  const info = benchmark.data;

  // Entrées en cours de relecture ; `null` tant qu'aucun brouillon n'est à relire.
  const [entries, setEntries] = useState<BenchmarkEntry[] | null>(null);

  // ── 1. Brouillon ───────────────────────────────────────────────────────────
  const [size, setSize] = useState(DRAFT_DEFAULT_SIZE);
  const [draftId, setDraftId] = useState<string | null>(null);
  const draft = useQuery<BenchmarkDraftJob>({
    queryKey: ['calibration-draft', draftId],
    queryFn: async () => (await qualityBenchmarkApi.getDraftJob(draftId!)).data,
    enabled: !!draftId,
    refetchInterval: q => (isRunning(q.state.data) ? 2000 : false),
  });
  const draftJob = draft.data;

  const startDraft = useMutation({
    mutationFn: async () => (await qualityBenchmarkApi.startDraft(size)).data as { jobId: string },
    onSuccess: data => { setDraftId(data.jobId); setEntries(null); },
    onError: error => toast.error(
      apiErrorStatus(error) === 409 ? t('calibration.draftAlreadyRunning') : t('calibration.draftStartFailed'),
      { description: apiErrorStatus(error) === 409 ? undefined : apiErrorMessage(error) }),
  });
  const cancelDraft = useMutation({
    mutationFn: () => qualityBenchmarkApi.cancelDraft(draftId!),
    onError: () => toast.error(t('calibration.draftCancelFailed')),
  });

  // ── 2. Relecture ───────────────────────────────────────────────────────────
  useEffect(() => {
    // Le brouillon terminé (ou interrompu, avec ce qu'il a produit) devient le texte à relire.
    // Un brouillon vide n'ouvre pas la relecture : elle bloquerait le lancement sans rien à relire.
    if (!draftJob || isRunning(draftJob) || entries !== null || draftJob.items.length === 0) return;
    if (draftJob.status === 'COMPLETED' || draftJob.status === 'CANCELLED') setEntries(draftToEntries(draftJob.items));
  }, [draftJob, entries]);

  const save = useMutation({
    mutationFn: async () => (await qualityBenchmarkApi.saveBenchmark(entries ?? [])).data,
    onSuccess: saved => {
      toast.success(t('calibration.saved', { count: saved.total }));
      setEntries(null);
      setDraftId(null);
      queryClient.setQueryData(['calibration-benchmark'], saved);
    },
    onError: error => toast.error(t('calibration.saveFailed'), { description: apiErrorMessage(error) }),
  });

  const edit = (index: number, patch: Partial<BenchmarkEntry>) =>
    setEntries(prev => prev && prev.map((e, i) => (i === index ? { ...e, ...patch } : e)));

  // ── 3. Grille ──────────────────────────────────────────────────────────────
  const [grid, setGrid] = useState<GridState>(DEFAULT_GRID);
  const [topK, setTopK] = useState(5);
  const [runs, setRuns] = useState(1);
  const arms = armCount(grid);
  const overCap = arms > MAX_ARMS;

  const calibrate = useMutation({
    mutationFn: async () => (await ablationApi.calibrate(toCalibrationBody(grid, topK, runs))).data as { jobId: string },
    onSuccess: data => onLaunched(data.jobId),
    onError: error => toast.error(
      apiErrorStatus(error) === 409 ? t('optimization.alreadyRunning') : t('optimization.startFailed'),
      { description: apiErrorStatus(error) === 409 ? undefined : apiErrorMessage(error) }),
  });

  const invalid = entries ? invalidEntries(entries) : [];
  const share = entries ? unanswerableShare(entries) : 0;
  const questions = info?.total ?? 0;
  const configured = info?.source === 'CONFIGURED';

  return (
    <section className="bg-surface-container-low border border-outline-variant/10 p-5 space-y-5" aria-labelledby="calibration-title">
      <div>
        <h3 id="calibration-title" className="font-headline text-xs uppercase tracking-widest text-on-surface-variant">{t('calibration.title')}</h3>
        <p className="text-[11px] text-on-surface-variant mt-1 max-w-3xl">{t('calibration.intro')}</p>
      </div>

      {/* 1. Jeu d'évaluation */}
      <div className="space-y-3">
        <h4 className="font-headline text-sm font-bold">{t('calibration.step1')}</h4>
        {info && (
          <p
            role={info.source === 'BUNDLED' ? 'alert' : undefined}
            className={`text-xs px-3 py-2 border ${info.source === 'BUNDLED' ? 'text-secondary bg-secondary/10 border-secondary/30' : 'text-on-surface-variant bg-surface-container border-outline-variant/20'}`}
          >
            {info.source === 'BUNDLED' && t('calibration.sourceBundled')}
            {info.source === 'CURATED' && t('calibration.sourceCurated', { total: info.total, answerable: info.answerable, unanswerable: info.unanswerable, withSources: info.withExpectedSources })}
            {info.source === 'CONFIGURED' && t('calibration.sourceConfigured', { path: info.path ?? '', total: info.total })}
          </p>
        )}
        {info && info.source !== 'BUNDLED' && info.withExpectedSources < info.answerable && (
          <p className="text-[11px] text-secondary">{t('calibration.partialSources', { withSources: info.withExpectedSources, answerable: info.answerable })}</p>
        )}

        {!configured && (
          <div className="flex flex-wrap items-end gap-3">
            <label className="text-xs text-on-surface-variant">
              {t('calibration.sizeLabel')}
              <input type="number" min={5} max={DRAFT_MAX_SIZE} value={size}
                onChange={e => setSize(Math.max(5, Math.min(DRAFT_MAX_SIZE, Number(e.target.value) || DRAFT_DEFAULT_SIZE)))}
                className="block mt-1 w-20 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs" />
            </label>
            <Button variant="outline" icon="auto_awesome" onClick={() => startDraft.mutate()}
              disabled={isRunning(draftJob) || entries !== null} loading={startDraft.isPending}>
              {t('calibration.draftButton')}
            </Button>
            <span className="text-[11px] text-on-surface-variant">{t('calibration.draftHint')}</span>
          </div>
        )}

        {isRunning(draftJob) && draftJob && (
          <div className="space-y-1.5" role="status">
            <div className="flex items-center justify-between gap-3 text-xs">
              <span>{t('calibration.drafting', { processed: draftJob.processed, total: draftJob.total })}</span>
              <Button size="sm" variant="outline" onClick={() => cancelDraft.mutate()} disabled={cancelDraft.isPending}>{t('calibration.cancel')}</Button>
            </div>
            <div className="w-full bg-outline-variant/20 h-1.5">
              <div className="h-full bg-secondary transition-all duration-500" style={{ width: `${Math.round((draftJob.processed / Math.max(1, draftJob.total)) * 100)}%` }} />
            </div>
          </div>
        )}
        {draftJob?.status === 'FAILED' && (
          <p role="alert" className="text-xs text-error bg-error/10 border border-error/30 px-3 py-2">
            {t('calibration.draftFailed', { error: draftJob.error ?? t('optimization.unknownError') })}
          </p>
        )}
        {draftJob && !isRunning(draftJob) && draftJob.status !== 'FAILED' && draftJob.items.length === 0 && (
          <p className="text-xs text-secondary">{t('calibration.draftEmpty')}</p>
        )}
      </div>

      {/* 2. Relecture */}
      {entries && entries.length > 0 && (
        <div className="space-y-3">
          <h4 className="font-headline text-sm font-bold">{t('calibration.step2')}</h4>
          <p className="text-[11px] text-on-surface-variant max-w-3xl">{t('calibration.reviewHint')}</p>
          <ul className="space-y-3">
            {entries.map((e, i) => (
              <li key={i} className={`border p-3 space-y-2 ${invalid.includes(i) ? 'border-error/40' : 'border-outline-variant/20'}`}>
                <div className="flex items-start justify-between gap-3">
                  <span className="text-[10px] uppercase tracking-widest text-outline">
                    {e.answerable ? (e.expectedSources[0] ?? t('calibration.noSource')) : t('calibration.outOfCorpus')}
                  </span>
                  <button type="button" onClick={() => setEntries(prev => prev && prev.filter((_, j) => j !== i))}
                    aria-label={t('calibration.removeQuestion', { n: i + 1 })}
                    className="material-symbols-outlined text-[16px] text-outline hover:text-error">delete</button>
                </div>
                <label className="block text-[11px] text-on-surface-variant">
                  {t('calibration.questionLabel', { n: i + 1 })}
                  <textarea value={e.question} rows={2} onChange={ev => edit(i, { question: ev.target.value })}
                    className="block mt-0.5 w-full bg-surface-container border border-outline-variant/20 px-2 py-1 text-xs text-on-surface" />
                </label>
                {e.answerable && (
                  <label className="block text-[11px] text-on-surface-variant">
                    {t('calibration.referenceLabel', { n: i + 1 })}
                    <textarea value={e.reference ?? ''} rows={2} onChange={ev => edit(i, { reference: ev.target.value })}
                      className="block mt-0.5 w-full bg-surface-container border border-outline-variant/20 px-2 py-1 text-xs text-on-surface" />
                  </label>
                )}
              </li>
            ))}
          </ul>

          <div className="flex flex-wrap items-center gap-3">
            <Button variant="outline" icon="add" onClick={() => setEntries(prev => prev && [...prev, outOfCorpusEntry()])}>
              {t('calibration.addOutOfCorpus')}
            </Button>
            <span className={`text-xs ${share < UNANSWERABLE_TARGET.min ? 'text-secondary' : 'text-on-surface-variant'}`}>
              {t('calibration.share', { pct: Math.round(share * 100) })}
            </span>
          </div>
          {invalid.length > 0 && (
            <p role="alert" className="text-xs text-error">{t('calibration.incomplete', { count: invalid.length })}</p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button icon="save" onClick={() => save.mutate()} loading={save.isPending} disabled={invalid.length > 0}>{t('calibration.save')}</Button>
            <Button variant="ghost" onClick={() => { setEntries(null); setDraftId(null); }}>{t('calibration.discard')}</Button>
          </div>
        </div>
      )}

      {/* 3. Grille */}
      <div className="space-y-3">
        <h4 className="font-headline text-sm font-bold">{t('calibration.step3')}</h4>
        <fieldset className="space-y-1.5">
          <legend className="text-xs text-on-surface">{t('calibration.rerankLabel')}</legend>
          <p className="text-[11px] text-on-surface-variant">{t('calibration.rerankHint')}</p>
          <div className="flex flex-wrap gap-1.5">
            {RERANK_SCORES.map(v => (
              <Chip key={v} pressed={grid.rerankMinScores.includes(v)}
                onClick={() => setGrid(g => ({ ...g, rerankMinScores: toggled(g.rerankMinScores, v) }))}>{String(v)}</Chip>
            ))}
          </div>
        </fieldset>
        <fieldset className="space-y-1.5">
          <legend className="text-xs text-on-surface">{t('calibration.neighborsLabel')}</legend>
          <div className="flex flex-wrap gap-1.5">
            {NEIGHBOR_WINDOWS.map(v => (
              <Chip key={v} pressed={grid.neighborWindows.includes(v)}
                onClick={() => setGrid(g => ({ ...g, neighborWindows: toggled(g.neighborWindows, v) }))}>{String(v)}</Chip>
            ))}
          </div>
        </fieldset>
        <fieldset className="space-y-1.5">
          <legend className="text-xs text-on-surface">{t('calibration.dedupLabel')}</legend>
          <div className="flex flex-wrap gap-1.5">
            {DEDUP_STRATEGIES.map(v => (
              <Chip key={v} pressed={grid.dedupStrategies.includes(v)}
                onClick={() => setGrid(g => ({ ...g, dedupStrategies: toggled(g.dedupStrategies, v) }))}>{v}</Chip>
            ))}
          </div>
        </fieldset>
        <p className="text-[11px] text-on-surface-variant">{t('calibration.keepConfigured')}</p>

        <div className="flex flex-wrap items-center gap-4">
          <label className="text-xs text-on-surface-variant flex items-center gap-2">
            {t('optimization.topK')}
            <input type="number" min={1} max={20} value={topK}
              onChange={e => setTopK(Math.max(1, Math.min(20, Number(e.target.value) || 5)))}
              className="w-16 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs" />
          </label>
          <label className="text-xs text-on-surface-variant flex items-center gap-2" title={t('optimization.runsHint')}>
            {t('optimization.runsLabel')}
            <input type="number" min={1} max={10} value={runs}
              onChange={e => setRuns(Math.max(1, Math.min(10, Number(e.target.value) || 1)))}
              className="w-16 bg-surface-container border border-outline-variant/20 px-2 py-1 text-on-surface text-xs" />
          </label>
          <Button icon="tune" onClick={() => calibrate.mutate()} loading={calibrate.isPending}
            disabled={overCap || questions === 0 || isRunning(draftJob) || entries !== null}>
            {t('calibration.launch')}
          </Button>
        </div>
        <p className={`text-xs ${overCap ? 'text-error' : 'text-on-surface-variant'}`} role={overCap ? 'alert' : undefined}>
          {overCap
            ? t('calibration.overCap', { arms, max: MAX_ARMS })
            : t('calibration.estimate', { arms, questions })}
        </p>
        {entries !== null && <p className="text-[11px] text-secondary">{t('calibration.saveFirst')}</p>}
      </div>
    </section>
  );
};

export default CalibrationWizard;
