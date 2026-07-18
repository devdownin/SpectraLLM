import { useState, useEffect } from 'react';
import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { evaluationApi } from '../services/api';
import type { EvaluationReport, EvaluationScore, ModelComparisonReport } from '../types/api';
import ScoreRadar from '../components/charts/ScoreRadar';
import ModelComparisonPanel from '../components/ModelComparisonPanel';
import BatchEvaluateDialog from '../components/BatchEvaluateDialog';
import AbComparisonView from '../components/AbComparisonView';
import Skeleton from '../components/Skeleton';
import { EmptyState, Button } from '../components/ui';

const STATUS_COLOR: Record<string, string> = {
  PENDING:   'text-on-surface-variant',
  RUNNING:   'text-secondary',
  COMPLETED: 'text-primary',
  FAILED:    'text-error',
  CANCELLED: 'text-on-surface-variant',
};

function ScoreBar({ score, color = 'bg-primary' }: { score: number; color?: string }) {
  const pct = Math.round((score / 10) * 100);
  return (
    <div className="flex items-center gap-2">
      <div className="flex-1 bg-outline-variant h-1.5 relative">
        <div className={`absolute top-0 left-0 h-full ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="text-xs font-bold font-headline w-8 text-right">{score.toFixed(1)}</span>
    </div>
  );
}

function ProgressBar({ value, max }: { value: number; max: number }) {
  const pct = max > 0 ? Math.round((value / max) * 100) : 0;
  return (
    <div className="flex items-center gap-2 mt-1">
      <div className="flex-1 bg-outline-variant h-1 relative">
        <div className="absolute top-0 left-0 h-full bg-secondary transition-all duration-500"
             style={{ width: `${pct}%` }} />
      </div>
      <span className="font-label text-[11px] text-on-surface-variant">{value}/{max}</span>
    </div>
  );
}

function ScoreDetail({ score }: { score: EvaluationScore }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const category = t(`comparison.category.${score.category}`, score.category);
  return (
    <div className="border-b border-outline-variant/10 last:border-0">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full text-left px-4 py-3 flex items-center gap-3 hover:bg-surface-container-high/40 transition-colors"
        aria-expanded={open}
        aria-label={t('comparison.scoreDetailAria', { score: score.score.toFixed(0), category })}
      >
        <span className="font-headline text-xs font-bold w-8 shrink-0"
              style={{ color: score.score >= 7 ? 'var(--color-primary)' : score.score >= 4 ? 'var(--color-secondary)' : 'var(--color-error)' }}>
          {score.score.toFixed(0)}/10
        </span>
        <span className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant w-20 shrink-0">
          {category}
        </span>
        <span className="text-xs text-on-surface truncate flex-1">{score.question}</span>
        <span className="text-[11px] text-on-surface-variant shrink-0">{open ? '▲' : '▼'}</span>
      </button>
      {open && (
        <div className="px-4 pb-4 space-y-3 bg-surface-container-low/20">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="bg-surface-container-low p-3 rounded border border-outline-variant/10">
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('comparison.referenceAnswer')}</p>
              <p className="text-xs text-on-surface-variant leading-relaxed">{score.referenceAnswer}</p>
            </div>
            <div className={`p-3 rounded border ${score.score >= 7 ? 'bg-primary/5 border-primary/20' : score.score >= 4 ? 'bg-secondary/5 border-secondary/20' : 'bg-error/5 border-error/20'}`}>
              <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">{t('comparison.modelAnswer')}</p>
              <p className="text-xs text-on-surface leading-relaxed">{score.modelAnswer}</p>
            </div>
          </div>
          <div className="bg-surface-container-low p-3 rounded border border-outline-variant/10 mt-3">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('comparison.judgeJustification')}</p>
            <p className="text-xs italic text-on-surface-variant">{score.justification}</p>
          </div>
          <p className="font-label text-[10px] text-on-surface-variant">{t('comparison.source', { source: score.source })}</p>
        </div>
      )}
    </div>
  );
}

const Comparison: FC = () => {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<EvaluationReport | null>(null);
  const [isTriggering, setIsTriggering] = useState(false);

  const formatDate = (iso: string) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' });

  const { data: reports = [], isLoading } = useQuery({
    queryKey: ['evaluation-reports'],
    queryFn: async (): Promise<EvaluationReport[]> =>
      (((await evaluationApi.getAll()).data ?? []) as EvaluationReport[])
        .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()),
    // Polling 5 s tant qu'une évaluation tourne, sinon désactivé.
    refetchInterval: (query) => {
      const data = query.state.data as EvaluationReport[] | undefined;
      return data?.some(r => r.status === 'RUNNING' || r.status === 'PENDING') ? 5000 : false;
    },
  });

  const [searchTerm, setSearchTerm] = useState('');

  // ── Mode comparaison multi-modèles ──────────────────────────────────────────
  const [compareIds, setCompareIds] = useState<string[]>([]);
  const [compareMode, setCompareMode] = useState(false);
  const [baselineId, setBaselineId] = useState<string | undefined>(undefined);
  const [batchOpen, setBatchOpen] = useState(false);
  const [abMode, setAbMode] = useState(false);

  // Pré-sélectionne les évaluations issues d'un batch dès qu'elles sont complétées.
  const handleBatchSubmitted = (evalIds: string[]) => {
    if (evalIds.length >= 2) {
      setCompareIds(evalIds);
      setCompareMode(true);
      setBaselineId(undefined);
    }
  };

  const toggleCompare = (evalId: string) => {
    setCompareIds(prev =>
      prev.includes(evalId) ? prev.filter(id => id !== evalId) : [...prev, evalId]
    );
  };

  // Clés stables pour le cache : ordre des ids indifférent.
  const sortedCompareIds = [...compareIds].sort();
  // Repoll tant qu'une évaluation sélectionnée est encore en cours.
  const comparingRunning = reports.some(
    r => compareIds.includes(r.evalId) && (r.status === 'RUNNING' || r.status === 'PENDING')
  );
  const { data: comparison, isLoading: isComparing, error: compareError } =
    useQuery<ModelComparisonReport>({
      queryKey: ['evaluation-compare', sortedCompareIds, baselineId],
      queryFn: async () =>
        (await evaluationApi.compare(compareIds, baselineId)).data as ModelComparisonReport,
      enabled: compareMode && compareIds.length >= 2,
      refetchInterval: comparingRunning ? 5000 : false,
    });

  // Synchronise la sélection quand la liste change (préserve le rapport choisi).
  useEffect(() => {
    setSelected(prev =>
      prev ? (reports.find(r => r.evalId === prev.evalId) ?? reports[0] ?? null) : (reports[0] ?? null)
    );
  }, [reports]);

  // Purge uniquement les sélections de comparaison qui n'existent plus (supprimées).
  // Les évaluations en cours sont conservées : la comparaison se met à jour à leur achèvement.
  useEffect(() => {
    const knownIds = new Set(reports.map(r => r.evalId));
    setCompareIds(prev => {
      const next = prev.filter(id => knownIds.has(id));
      return next.length === prev.length ? prev : next;
    });
  }, [reports]);

  const handleExportComparison = () => {
    if (!comparison) return;
    const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(comparison, null, 2));
    const a = document.createElement('a');
    a.setAttribute("href", dataStr);
    a.setAttribute("download", `comparison_${comparison.models.length}models.json`);
    document.body.appendChild(a);
    a.click();
    a.remove();
  };

  const handleNewEvaluation = async () => {
    setIsTriggering(true);
    try {
      await evaluationApi.submit();
      await queryClient.invalidateQueries({ queryKey: ['evaluation-reports'] });
    } catch (err: any) {
      // Toast (et non alert natif) : cohérent avec le reste de l'application.
      toast.error(t('comparison.startFailed'), {
        description: err?.response?.data?.detail ?? t('comparison.startFailedHint'),
      });
    } finally {
      setIsTriggering(false);
    }
  };

  const categories = selected ? Object.entries(selected.scoresByCategory) : [];

  const filteredReports = reports.filter(r => r.modelName.toLowerCase().includes(searchTerm.toLowerCase()));

  const handleExportSelected = () => {
    if (!selected) return;
    const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(selected, null, 2));
    const downloadAnchorNode = document.createElement('a');
    downloadAnchorNode.setAttribute("href", dataStr);
    downloadAnchorNode.setAttribute("download", `evaluation_${selected.modelName}_${selected.evalId}.json`);
    document.body.appendChild(downloadAnchorNode);
    downloadAnchorNode.click();
    downloadAnchorNode.remove();
  };

  return (
    <div className="space-y-8 animate-in fade-in duration-700">
      <BatchEvaluateDialog
        open={batchOpen}
        onClose={() => setBatchOpen(false)}
        onSubmitted={handleBatchSubmitted}
      />
      {/* Header */}
      <header className="flex items-end justify-between">
        <div>
          <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">
            {t('comparison.kicker')}
          </p>
          <h2 className="font-headline text-3xl font-bold tracking-tighter">{t('comparison.title')}</h2>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setAbMode(m => !m)}
            className={`px-4 py-2 font-label text-[11px] uppercase tracking-widest transition-colors border
                       ${abMode
                          ? 'bg-secondary text-on-secondary border-secondary'
                          : 'bg-transparent text-on-surface-variant border-outline-variant/30 hover:text-on-surface hover:bg-surface-container-high'}`}
            aria-pressed={abMode}
            title={t('comparison.abTitle')}
          >
            {abMode ? t('comparison.abExit') : t('comparison.abEnter')}
          </button>
          {!abMode && (
            <>
              <button
                onClick={() => setBatchOpen(true)}
                className="px-4 py-2 bg-transparent text-on-surface-variant font-label text-[11px] uppercase tracking-widest
                           border border-outline-variant/30 hover:text-on-surface hover:bg-surface-container-high transition-colors"
                aria-label={t('comparison.batchTitle')}
                title={t('comparison.batchTitle')}
              >
                {t('comparison.batch')}
              </button>
              <button
                onClick={() => setCompareMode(m => !m)}
                disabled={compareIds.length < 2}
                className={`px-4 py-2 font-label text-[11px] uppercase tracking-widest transition-colors border
                           disabled:opacity-40 disabled:cursor-not-allowed
                           ${compareMode
                              ? 'bg-secondary text-on-secondary border-secondary'
                              : 'bg-transparent text-on-surface-variant border-outline-variant/30 hover:text-on-surface hover:bg-surface-container-high'}`}
                aria-pressed={compareMode}
                title={compareIds.length < 2 ? t('comparison.compareHintDisabled') : t('comparison.compareHintEnabled')}
              >
                {compareMode ? t('comparison.compareExit') : t('comparison.compareEnter', { count: compareIds.length })}
              </button>
              <button
                onClick={handleNewEvaluation}
                disabled={isTriggering}
                className="px-4 py-2 bg-primary text-on-primary font-label text-[11px] uppercase tracking-widest
                           hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed transition-opacity"
              >
                {isTriggering ? t('comparison.launching') : t('comparison.newEval')}
              </button>
            </>
          )}
        </div>
      </header>

      {abMode && <AbComparisonView />}

      {!abMode && (isLoading ? (
        <div className="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-6 items-start">
          <Skeleton className="h-64" />
          <Skeleton className="h-80" />
        </div>
      ) : reports.length === 0 ? (
        <div className="bg-surface-container rounded-xl ring-1 ring-white/[0.045]">
          <EmptyState
            icon="analytics"
            title={t('comparison.empty')}
            description={t('comparison.emptyHint')}
            action={
              <Button onClick={handleNewEvaluation} loading={isTriggering} icon="play_arrow">
                {t('comparison.newEval')}
              </Button>
            }
          />
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-6 items-start">
          {/* Evaluation list */}
          <div className="bg-surface-container divide-y divide-outline-variant/10">
            <div className="px-4 py-3 bg-surface-container-high flex flex-col gap-2">
               <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('comparison.history')}</p>
               <input
                 type="text"
                 placeholder={t('comparison.filterPlaceholder')}
                 value={searchTerm}
                 onChange={e => setSearchTerm(e.target.value)}
                 className="bg-surface-container-low text-xs text-on-surface px-2 py-1 outline-none border border-outline-variant/20 focus:border-primary/50"
                 aria-label={t('comparison.filterPlaceholder')}
               />
            </div>
            {filteredReports.map(r => (
              <div key={r.evalId} className="flex items-stretch">
              {r.status === 'COMPLETED' && (
                <label
                  className="flex items-center px-3 cursor-pointer hover:bg-surface-container-high/60"
                  title={t('comparison.selectForCompare')}
                >
                  <input
                    type="checkbox"
                    checked={compareIds.includes(r.evalId)}
                    onChange={() => toggleCompare(r.evalId)}
                    className="accent-secondary"
                    aria-label={t('comparison.selectModelAria', { name: r.modelName })}
                  />
                </label>
              )}
              <button
                onClick={() => setSelected(r)}
                className={`flex-1 min-w-0 text-left px-4 py-3 transition-colors hover:bg-surface-container-high/60
                  ${selected?.evalId === r.evalId ? 'bg-surface-container-high' : ''}`}
                aria-label={t('comparison.reportAria', { name: r.modelName, date: formatDate(r.startedAt) })}
                aria-current={selected?.evalId === r.evalId ? 'true' : 'false'}
              >
                <div className="flex items-center justify-between mb-0.5">
                  <span className="font-headline font-bold text-xs truncate pr-2">{r.modelName}</span>
                  <span className={`font-label text-[10px] uppercase tracking-widest shrink-0 ${STATUS_COLOR[r.status]}`}>
                    {t(`comparison.status.${r.status}`, r.status)}
                  </span>
                </div>
                <p className="font-label text-[10px] text-on-surface-variant">
                  {formatDate(r.startedAt)}
                </p>
                {r.status === 'COMPLETED' && (
                  <p className="font-headline text-sm font-bold mt-1">
                    {r.averageScore.toFixed(2)}<span className="text-[11px] font-normal text-on-surface-variant">/10</span>
                  </p>
                )}
                {r.status === 'RUNNING' && (
                  <ProgressBar value={r.processed} max={r.testSetSize} />
                )}
              </button>
              </div>
            ))}
          </div>

          {/* Comparison panel (multi-model) */}
          {compareMode && (
            compareIds.length < 2 ? (
              <div className="bg-surface-container p-8 text-center text-sm text-on-surface-variant">
                {t('comparison.needTwo')}
              </div>
            ) : isComparing ? (
              <Skeleton className="h-80" />
            ) : compareError ? (
              <div className="bg-error/10 text-error text-sm p-6">
                {t('comparison.compareFailed')}
              </div>
            ) : comparison ? (
              <ModelComparisonPanel
                report={comparison}
                baselineId={baselineId ?? comparison.models.find(m => m.baseline)?.evalId ?? comparison.models[0]?.evalId ?? ''}
                onBaselineChange={setBaselineId}
                onExport={handleExportComparison}
              />
            ) : null
          )}

          {/* Detail panel */}
          {!compareMode && selected && (
            <div className="space-y-6">
              {/* Overview */}
              <div className="bg-surface-container p-6 space-y-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('comparison.evaluatedModel')}</p>
                    <p className="font-headline font-bold text-lg">{selected.modelName}</p>
                    {selected.judgeModel && (
                      <p className="font-label text-[11px] text-on-surface-variant mt-0.5">
                        {t('comparison.judge')} <span className={selected.judgeModel === selected.modelName ? '' : 'text-secondary'}>{selected.judgeModel}</span>
                        {selected.judgeModel === selected.modelName && ` ${t('comparison.selfJudged')}`}
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-3">
                    {selected.status === 'COMPLETED' && (
                      <button
                        onClick={handleExportSelected}
                        className="px-3 py-1 text-[11px] font-label uppercase tracking-widest border border-outline-variant/30 text-on-surface-variant hover:bg-surface-container-high hover:text-on-surface transition-colors"
                      >
                        {t('comparison.exportJson')}
                      </button>
                    )}
                    <span className={`font-label text-[11px] uppercase tracking-widest px-3 py-1 border ${STATUS_COLOR[selected.status]}
                      ${selected.status === 'COMPLETED' ? 'border-primary/30' : 'border-outline-variant/30'}`}>
                      {t(`comparison.status.${selected.status}`, selected.status)}
                    </span>
                  </div>
                </div>

                {(selected.status === 'RUNNING' || selected.status === 'PENDING') && (
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('comparison.progress')}</p>
                      <span className="flex items-center gap-1 text-[10px] font-label uppercase tracking-widest text-secondary">
                        <span className="w-1.5 h-1.5 bg-secondary rounded-full animate-pulse inline-block" />
                        {selected.status === 'PENDING' ? t('comparison.pending') : t('comparison.running')}
                      </span>
                    </div>
                    <div className="relative w-full bg-outline-variant/20 h-1 overflow-hidden">
                      {selected.testSetSize > 0 ? (
                        <div className="absolute top-0 left-0 h-full bg-secondary transition-all duration-500"
                             style={{ width: `${Math.round((selected.processed / selected.testSetSize) * 100)}%` }} />
                      ) : null}
                      {selected.status === 'RUNNING' && (
                        <div className="absolute inset-0 scan-beam-secondary" />
                      )}
                      {selected.status === 'PENDING' && (
                        <div className="absolute inset-0 bg-gradient-to-r from-transparent via-secondary/30 to-transparent animate-pulse" />
                      )}
                    </div>
                    {selected.testSetSize > 0 && (
                      <p className="font-label text-[10px] text-on-surface-variant mt-0.5 text-right">
                        {t('comparison.pairsProgress', { done: selected.processed, total: selected.testSetSize })}
                      </p>
                    )}
                  </div>
                )}

                {selected.status === 'FAILED' && selected.error && (
                  <p className="text-xs text-error bg-error/10 px-3 py-2">{selected.error}</p>
                )}

                {(selected.status === 'COMPLETED' || selected.status === 'RUNNING') && selected.processed > 0 && (
                  <>
                    <div>
                      <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant mb-2">{t('comparison.globalScore')}</p>
                      <div className="flex items-baseline gap-2">
                        <span className="font-headline text-4xl font-bold">{selected.averageScore.toFixed(2)}</span>
                        <span className="text-on-surface-variant">/10</span>
                        <span className="font-label text-[11px] text-on-surface-variant ml-2">
                          {t('comparison.onPairs', { count: selected.processed })}
                        </span>
                      </div>
                      {(selected.avgLatencyMs > 0 || selected.avgTokensPerSec > 0) && (
                        <div className="flex items-center gap-4 mt-2">
                          {selected.avgLatencyMs > 0 && (
                            <span className="font-label text-[11px] text-on-surface-variant">
                              {t('comparison.latency')} <span className="text-on-surface font-bold">{(selected.avgLatencyMs / 1000).toFixed(2)}s</span> {t('comparison.perAnswer')}
                            </span>
                          )}
                          {selected.avgTokensPerSec > 0 && (
                            <span className="font-label text-[11px] text-on-surface-variant">
                              ~<span className="text-on-surface font-bold">{selected.avgTokensPerSec.toFixed(1)}</span> {t('comparison.tokPerSec')}
                            </span>
                          )}
                        </div>
                      )}
                    </div>

                    {categories.length > 0 && (
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 items-start">
                        {/* Score bars */}
                        <div>
                          <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant mb-3">{t('comparison.byCategory')}</p>
                          <div className="space-y-2">
                            {categories.map(([cat, avg]) => (
                              <div key={cat} className="grid grid-cols-[100px_1fr] gap-3 items-center">
                                <span className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
                                  {t(`comparison.category.${cat}`, cat)}
                                </span>
                                <ScoreBar score={avg}
                                  color={cat === 'negative' ? 'bg-secondary' : 'bg-primary'} />
                              </div>
                            ))}
                          </div>
                        </div>
                        {/* Radar chart */}
                        {categories.length >= 3 && (
                          <div className="h-48">
                            <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant mb-1">{t('comparison.radar')}</p>
                            <ScoreRadar scoresByCategory={selected.scoresByCategory} />
                          </div>
                        )}
                      </div>
                    )}
                  </>
                )}
              </div>

              {/* Per-pair details */}
              {selected.scores.length > 0 && (
                <div className="bg-surface-container">
                  <p className="px-4 py-3 font-label text-[11px] uppercase tracking-widest text-on-surface-variant bg-surface-container-high border-b border-outline-variant/10 flex items-center justify-between">
                    <span>{t('comparison.pairDetails', { count: selected.scores.length })}</span>
                  </p>
                  {selected.scores.map((s, i) => (
                    <ScoreDetail key={i} score={s} />
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
};

export default Comparison;
