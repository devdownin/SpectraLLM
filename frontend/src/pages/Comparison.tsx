import { useViewState } from '../hooks/useViewState';
import { useState, useEffect } from 'react';
import { useContextSearchParams } from '../hooks/useContextSearchParams';
import { DetailPanel } from '../components/ui/DetailPanel';
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
import { EmptyState, Button, PageHeader, CountUp, StatusBadge, WorkList, WorkListRow, ListFeedback, ListTools } from '../components/ui';
import { apiErrorMessage } from '../lib/apiError';


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
  const [params, setParams] = useContextSearchParams();
  const targetEval = params.get('eval');
  const targetAb = params.get('ab');
  const requested = useQuery<EvaluationReport>({ queryKey: ['evaluation-target', targetEval], queryFn: async () => (await evaluationApi.get(targetEval!)).data, enabled: !!targetEval, refetchInterval: q => ['PENDING','RUNNING'].includes(q.state.data?.status ?? '') ? 5000 : false });
  const [localSelected, setSelected] = useState<EvaluationReport | null>(null);
  const selected = targetEval ? requested.data ?? null : localSelected;
  const [isTriggering, setIsTriggering] = useState(false);

  const formatDate = (iso: string) =>
    new Date(iso).toLocaleString(i18n.language, { dateStyle: 'short', timeStyle: 'short' });

  const { data: reports = [], isLoading, isError: listError, refetch: reloadReports } = useQuery({
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

  const [searchTerm, setSearchTerm] = useViewState('comparison.search', '');
  const [listStatus, setListStatus] = useViewState('comparison.status', 'all');
  const [listSort, setListSort] = useViewState('comparison.sort', 'recent');

  // ── Mode comparaison multi-modèles ──────────────────────────────────────────
  const [compareIds, setCompareIds] = useState<string[]>([]);
  const [compareMode, setCompareMode] = useState(false);
  const [baselineId, setBaselineId] = useState<string | undefined>(undefined);
  const [batchOpen, setBatchOpen] = useState(false);
  const [abMode, setAbMode] = useViewState('comparison.abMode', Boolean(targetAb));
  useEffect(() => { if (targetAb) setAbMode(true); else if (targetEval) setAbMode(false); }, [targetAb, targetEval, setAbMode]);

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

  // A requested report never falls back to a different run.
  useEffect(() => {
    if (targetEval) return;
    setSelected(prev => prev ? reports.find(r => r.evalId === prev.evalId) ?? null : null);
  }, [reports, targetEval]);
  const closeReport = () => {
    setSelected(null);
    if (targetEval) { const next = new URLSearchParams(params); next.delete('eval'); setParams(next, { replace: true }); }
  };

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
    } catch (err) {
      // Toast (et non alert natif) : cohérent avec le reste de l'application.
      toast.error(t('comparison.startFailed'), {
        description: apiErrorMessage(err, t('comparison.startFailedHint')),
      });
    } finally {
      setIsTriggering(false);
    }
  };

  const categories = selected ? Object.entries(selected.scoresByCategory) : [];
  // R8 — ventilation thématique : « faible sur la réglementation », par opposition à
  // scoresByCategory qui ventile par type d'exercice (q/r, résumé, refus).
  const documentCategories = selected ? Object.entries(selected.scoresByDocumentCategory ?? {}) : [];

  const openReport = (report: EvaluationReport) => { setSelected(report); const next = new URLSearchParams(params); next.set('eval', report.evalId); setParams(next); };
  const filteredReports = reports.filter(r => (listStatus === 'all' || r.status === listStatus) && `${r.modelName} ${r.evalId}`.toLowerCase().includes(searchTerm.toLowerCase())).sort((a, b) => listSort === 'name' ? a.modelName.localeCompare(b.modelName) : b.startedAt.localeCompare(a.startedAt));

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
    <div className="space-y-6 animate-in fade-in duration-700">
      <BatchEvaluateDialog
        open={batchOpen}
        onClose={() => setBatchOpen(false)}
        onSubmitted={handleBatchSubmitted}
      />
      {/* Header */}
      <PageHeader
        kicker={t('comparison.kicker')}
        title={t('comparison.title')}
        secondaryActions={[
          { id: 'ab', label: abMode ? t('comparison.abExit') : t('comparison.abEnter'), icon: 'compare_arrows', pressed: abMode, onSelect: () => setAbMode(value => !value) },
          ...(!abMode ? [
            { id: 'batch', label: t('comparison.batchTitle'), icon: 'playlist_add', onSelect: () => setBatchOpen(true) },
            { id: 'compare', label: compareMode ? t('comparison.compareExit') : t('comparison.compareEnter', { count: compareIds.length }), icon: 'compare', pressed: compareMode, disabled: compareIds.length < 2, disabledReason: t('comparison.needTwo'), onSelect: () => setCompareMode(value => !value) },
          ] : []),
        ]}
        primaryAction={!abMode && <Button onClick={handleNewEvaluation} disabled={isTriggering} loading={isTriggering} icon="add">{isTriggering ? t('comparison.launching') : t('comparison.newEval')}</Button>}
      />

      {targetEval && requested.isError && <p role="alert">{t('detail.missingTarget')}</p>}
      {targetEval && requested.isLoading && <p role="status">{t('activity.loading')}</p>}
      {abMode && <AbComparisonView />}

      {!abMode && (isLoading ? (
        <ListFeedback loading><span /></ListFeedback>
      ) : listError && !reports.length ? (<ListFeedback error retry={() => { void reloadReports(); }}><span /></ListFeedback>) : reports.length === 0 && !selected ? (
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
        <div className="space-y-6">
          <ListTools resultCount={filteredReports.length} search={searchTerm} onSearch={setSearchTerm} status={listStatus} onStatus={setListStatus} statuses={['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']} sort={listSort} onSort={setListSort} onReset={() => { setSearchTerm(''); setListStatus('all'); setListSort('recent'); }} />
          <ListFeedback error={listError} empty={!filteredReports.length} retry={() => { void reloadReports(); }} action={<Button variant="outline" onClick={() => { setSearchTerm(''); setListStatus('all'); }}>{t('workList.reset')}</Button>}>
            {!!filteredReports.length && <WorkList viewKey="comparison" filters={JSON.stringify([searchTerm, listStatus, listSort])} label={t('comparison.history')}>{filteredReports.map(r => <WorkListRow key={r.evalId} selected={selected?.evalId === r.evalId}
              identity={<div className="flex gap-3 items-center">{r.status === 'COMPLETED' && <input type="checkbox" checked={compareIds.includes(r.evalId)} onChange={() => toggleCompare(r.evalId)} aria-label={t('comparison.selectModelAria', { name: r.modelName })} className="accent-secondary" />}<div><button onClick={() => openReport(r)} className="font-medium text-left hover:text-primary" aria-label={t('comparison.reportAria', { name: r.modelName, date: formatDate(r.startedAt) })}>{r.modelName}</button><p className="text-xs break-all text-on-surface-variant">{r.evalId}</p></div></div>}
              status={<StatusBadge status={r.status} />}
              context={<div className="text-xs space-y-1"><p>{t('comparison.judge')}: {r.judgeModel || '—'}</p>{r.status === 'COMPLETED' ? <p>{r.averageScore.toFixed(2)}/10</p> : <ProgressBar value={r.processed} max={r.testSetSize} />}{r.error && <p className="text-error break-all">{r.error}</p>}</div>}
              updated={<time dateTime={r.completedAt ?? r.startedAt}>{formatDate(r.completedAt ?? r.startedAt)}</time>}
              actions={<Button variant="outline" size="sm" onClick={() => openReport(r)}>{t(['PENDING', 'RUNNING'].includes(r.status) ? 'workList.follow' : r.status === 'COMPLETED' ? 'workList.result' : r.status === 'FAILED' ? 'workList.inspect' : 'workList.open')}</Button>}
            />)}</WorkList>}
          </ListFeedback>

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
            <DetailPanel title={selected.modelName} onClose={closeReport}
              summary={<div className="flex flex-wrap gap-3"><StatusBadge status={selected.status} /><span className="text-sm break-all">{selected.evalId}</span></div>}
              history={<dl className="text-sm space-y-2"><dt>{t('detail.started')}</dt><dd>{formatDate(selected.startedAt)}</dd><dt>{t('detail.finished')}</dt><dd>{selected.completedAt ? formatDate(selected.completedAt) : '—'}</dd></dl>}
              actions={<div className="flex gap-2"><Button variant="outline" onClick={handleExportSelected} icon="download">{t('detail.export')}</Button><Button variant="outline" onClick={closeReport}>{t('detail.close')}</Button></div>}
              details={<div className="space-y-6">
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
                    <StatusBadge status={selected.status} />
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
                        <span className="font-headline text-4xl font-bold">
                          <CountUp to={selected.averageScore} decimals={2} />
                        </span>
                        <span className="text-on-surface-variant">/10</span>
                        <span className="font-label text-[11px] text-on-surface-variant ml-2">
                          {t('comparison.onPairs', { count: selected.processed })}
                        </span>
                      </div>
                      {(selected.avgLatencyMs > 0 || selected.avgTokensPerSec > 0) && (
                        <div className="flex items-center gap-4 mt-2">
                          {selected.avgLatencyMs > 0 && (
                            <span className="font-label text-[11px] text-on-surface-variant">
                              {t('comparison.latency')} <span className="text-on-surface font-bold"><CountUp to={selected.avgLatencyMs / 1000} decimals={2} suffix="s" /></span> {t('comparison.perAnswer')}
                            </span>
                          )}
                          {selected.avgTokensPerSec > 0 && (
                            <span className="font-label text-[11px] text-on-surface-variant">
                              ~<span className="text-on-surface font-bold"><CountUp to={selected.avgTokensPerSec} decimals={1} /></span> {t('comparison.tokPerSec')}
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

                    {/* R8 — diagnostic thématique : sur quel sujet le modèle est-il faible ?
                        Trié du plus faible au plus fort, car c'est le point bas qui appelle
                        une action sur le corpus. */}
                    {documentCategories.length > 0 && (
                      <div className="mt-6">
                        <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant mb-1">
                          {t('comparison.byDocumentCategory')}
                        </p>
                        <p className="text-[11px] text-outline mb-3">{t('comparison.byDocumentCategoryHint')}</p>
                        <div className="space-y-2">
                          {[...documentCategories].sort((a, b) => a[1] - b[1]).map(([cat, avg]) => (
                            <div key={cat} className="grid grid-cols-[120px_1fr] gap-3 items-center">
                              <span className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant truncate">
                                {cat}
                              </span>
                              <ScoreBar score={avg} color={avg < 6 ? 'bg-error' : 'bg-secondary'} />
                            </div>
                          ))}
                        </div>
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
            </div>} />
          )}
        </div>
      ))}
    </div>
  );
};

export default Comparison;
