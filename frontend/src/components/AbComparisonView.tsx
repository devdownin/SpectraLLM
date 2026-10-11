import { useViewState } from '../hooks/useViewState';
import { WorkList, WorkListRow, ListFeedback, ListTools } from './ui';
import { DetailPanel } from './ui/DetailPanel';
import { Button } from './ui';
import { useContextSearchParams } from '../hooks/useContextSearchParams';
import { StatusBadge } from './ui';
import { useState, useEffect } from 'react';
import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { configApi, evaluationApi } from '../services/api';
import type { AbComparisonReport, AbItem } from '../types/api';
import { apiErrorMessage } from '../lib/apiError';

interface RegisteredModel { id?: string; name?: string; type?: string; }

const WINNER_KEY: Record<string, string> = { A: 'ab.winA', B: 'ab.winB', TIE: 'ab.tie' };

function WinBar({ a, t, b }: { a: number; t: number; b: number }) {
  const pct = (v: number) => `${Math.round(v * 100)}%`;
  return (
    <div className="flex h-3 w-full overflow-hidden bg-surface-container-low">
      <div className="bg-primary h-full" style={{ width: pct(a) }} title={`A ${pct(a)}`} />
      <div className="bg-outline-variant/40 h-full" style={{ width: pct(t) }} title={`= ${pct(t)}`} />
      <div className="bg-secondary h-full" style={{ width: pct(b) }} title={`B ${pct(b)}`} />
    </div>
  );
}

function AbItemRow({ item, modelA, modelB }: { item: AbItem; modelA: string; modelB: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const color = item.winner === 'A' ? 'text-primary' : item.winner === 'B' ? 'text-secondary' : 'text-on-surface-variant';
  return (
    <div className="border-b border-outline-variant/10 last:border-0">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full text-left px-4 py-3 flex items-center gap-3 hover:bg-surface-container-high/40 transition-colors"
        aria-expanded={open}
      >
        <span className={`font-label text-[10px] uppercase tracking-widest w-16 shrink-0 ${color}`}>
          {t(WINNER_KEY[item.winner], item.winner)}
        </span>
        <span className="text-xs text-on-surface truncate flex-1">{item.question}</span>
        <span className="text-[11px] text-on-surface-variant shrink-0">{open ? '▲' : '▼'}</span>
      </button>
      {open && (
        <div className="px-4 pb-4 grid grid-cols-1 md:grid-cols-2 gap-3">
          <div className={`p-3 rounded border ${item.winner === 'A' ? 'bg-primary/5 border-primary/30' : 'bg-surface-container-low border-outline-variant/10'}`}>
            <p className="font-label text-[10px] uppercase tracking-widest text-primary mb-1">A · {modelA}</p>
            <p className="text-xs text-on-surface leading-relaxed">{item.answerA}</p>
          </div>
          <div className={`p-3 rounded border ${item.winner === 'B' ? 'bg-secondary/5 border-secondary/30' : 'bg-surface-container-low border-outline-variant/10'}`}>
            <p className="font-label text-[10px] uppercase tracking-widest text-secondary mb-1">B · {modelB}</p>
            <p className="text-xs text-on-surface leading-relaxed">{item.answerB}</p>
          </div>
          <div className="md:col-span-2 bg-surface-container-low p-3 rounded border border-outline-variant/10">
            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">{t('ab.reference')}</p>
            <p className="text-xs text-on-surface-variant leading-relaxed">{item.reference}</p>
          </div>
        </div>
      )}
    </div>
  );
}

/** Comparaison directe A/B (head-to-head) entre deux modèles. */
const AbComparisonView: FC = () => {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [modelA, setModelA] = useState('');
  const [modelB, setModelB] = useState('');
  const [size, setSize] = useState('');
  const [params, setParams] = useContextSearchParams();
  const targetAb = params.get('ab');
  const [selectedId, setSelectedId] = useState<string | null>(targetAb);
  const requested = useQuery<AbComparisonReport>({ queryKey: ['ab-target', targetAb], queryFn: async () => (await evaluationApi.getAb(targetAb!)).data, enabled: !!targetAb, refetchInterval: q => ['PENDING','RUNNING'].includes(q.state.data?.status ?? '') ? 4000 : false });
  const [search, setSearch] = useViewState('ab.search', '');
  const [status, setStatus] = useViewState('ab.status', 'all');
  const [sort, setSort] = useViewState('ab.sort', 'recent');
  const [submitting, setSubmitting] = useState(false);

  const { data: models = [] } = useQuery({
    queryKey: ['config-models'],
    queryFn: async (): Promise<RegisteredModel[]> => {
      const data = (await configApi.getModels()).data ?? [];
      return (data as RegisteredModel[]).filter(m => (m.type ?? 'chat') === 'chat');
    },
  });

  const { data: reports = [], isLoading, isError, refetch } = useQuery({
    queryKey: ['ab-reports'],
    queryFn: async (): Promise<AbComparisonReport[]> =>
      (((await evaluationApi.getAllAb()).data ?? []) as AbComparisonReport[])
        .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()),
    refetchInterval: (query) => {
      const data = query.state.data as AbComparisonReport[] | undefined;
      return data?.some(r => r.status === 'RUNNING' || r.status === 'PENDING') ? 4000 : false;
    },
  });

  const filtered = reports.filter(r => (status === 'all' || r.status === status) && `${r.modelA} ${r.modelB} ${r.abId}`.toLowerCase().includes(search.toLowerCase())).sort((a, b) => sort === 'name' ? a.modelA.localeCompare(b.modelA) : b.startedAt.localeCompare(a.startedAt));
  const selected = targetAb ? requested.data ?? null : reports.find(r => r.abId === selectedId) ?? null;
  useEffect(() => { if (targetAb) setSelectedId(targetAb); }, [targetAb]);
  const closeReport = () => {
    setSelectedId(null);
    if (targetAb) { const next = new URLSearchParams(params); next.delete('ab'); setParams(next, { replace: true }); }
  };
  const nameOf = (m: RegisteredModel) => m.name ?? m.id ?? '';

  const run = async () => {
    if (!modelA || !modelB || modelA === modelB) return;
    setSubmitting(true);
    try {
      const parsed = size.trim() ? parseInt(size, 10) : undefined;
      const res = await evaluationApi.submitAb(modelA, modelB, Number.isFinite(parsed) ? parsed : undefined);
      setSelectedId(res.data?.abId ?? null);
      if (targetAb && res.data?.abId) { const next = new URLSearchParams(params); next.set('ab', res.data.abId); setParams(next); }
      await queryClient.invalidateQueries({ queryKey: ['ab-reports'] });
      toast.success(t('ab.started'), { description: t('ab.startedDesc') });
    } catch (err) {
      toast.error(t('ab.startFailed'), { description: apiErrorMessage(err) });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      {targetAb && requested.isError && <p role="alert">{t('detail.missingTarget')}</p>}
      {/* Setup */}
      <div className="bg-surface-container p-6 space-y-4">
        <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('ab.newRun')}</p>
        <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_1fr_auto_auto] gap-3 items-end">
          <label className="space-y-1">
            <span className="font-label text-[10px] uppercase tracking-widest text-primary">{t('ab.modelA')}</span>
            <select value={modelA} onChange={e => setModelA(e.target.value)}
              className="w-full bg-surface-container-low text-sm px-2 py-2 outline-none border border-outline-variant/20 focus:border-primary/50">
              <option value="">{t('ab.select')}</option>
              {models.map(m => <option key={nameOf(m)} value={nameOf(m)}>{nameOf(m)}</option>)}
            </select>
          </label>
          <span className="text-on-surface-variant text-xs pb-2 text-center font-headline">{t('ab.vs')}</span>
          <label className="space-y-1">
            <span className="font-label text-[10px] uppercase tracking-widest text-secondary">{t('ab.modelB')}</span>
            <select value={modelB} onChange={e => setModelB(e.target.value)}
              className="w-full bg-surface-container-low text-sm px-2 py-2 outline-none border border-outline-variant/20 focus:border-secondary/50">
              <option value="">{t('ab.select')}</option>
              {models.map(m => <option key={nameOf(m)} value={nameOf(m)}>{nameOf(m)}</option>)}
            </select>
          </label>
          <label className="space-y-1">
            <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">{t('ab.testSize')}</span>
            <input type="number" min={1} placeholder={t('ab.auto')} value={size} onChange={e => setSize(e.target.value)}
              className="w-20 bg-surface-container-low text-sm px-2 py-2 outline-none border border-outline-variant/20 focus:border-primary/50" />
          </label>
          <button onClick={run} disabled={!modelA || !modelB || modelA === modelB || submitting}
            className="px-4 py-2 bg-primary text-on-primary font-label text-[11px] uppercase tracking-widest hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed transition-opacity">
            {submitting ? t('ab.starting') : t('ab.run')}
          </button>
        </div>
        {modelA && modelB && modelA === modelB && (
          <p className="text-xs text-error">{t('ab.samePick')}</p>
        )}
      </div>

      {isLoading ? (
        <ListFeedback loading><span /></ListFeedback>
      ) : isError && !reports.length ? (<ListFeedback error retry={() => { void refetch(); }}><span /></ListFeedback>) : reports.length === 0 && !selected ? (
        <div className="bg-surface-container p-8 text-center text-sm text-on-surface-variant">
          {t('ab.empty')}
        </div>
      ) : (
        <div className="space-y-6">
          <ListTools search={search} onSearch={setSearch} status={status} onStatus={setStatus} statuses={['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']} sort={sort} onSort={setSort} onReset={() => { setSearch(''); setStatus('all'); setSort('recent'); }} />
          <ListFeedback error={isError} empty={!filtered.length} retry={() => { void refetch(); }} action={<Button variant="outline" onClick={() => { setSearch(''); setStatus('all'); }}>{t('workList.reset')}</Button>}>
            {!!filtered.length && <WorkList viewKey="ab" filters={JSON.stringify([search, status, sort])} label={t('ab.history')}>{filtered.map(r => <WorkListRow key={r.abId}
              identity={<><p className="font-medium">{r.modelA} {t('ab.vs')} {r.modelB}</p><p className="text-xs text-on-surface-variant break-all">{r.abId}</p></>}
              status={<StatusBadge status={r.status} />}
              context={<p className="text-xs">{r.status === 'COMPLETED' ? `${r.aWins}–${r.ties}–${r.bWins}` : `${r.processed}/${r.testSetSize}`}</p>}
              updated={<time dateTime={r.completedAt ?? r.startedAt}>{new Date(r.completedAt ?? r.startedAt).toLocaleString(i18n.language)}</time>}
              actions={<Button variant="outline" size="sm" onClick={() => { setSelectedId(r.abId); const next = new URLSearchParams(params); next.set('ab', r.abId); setParams(next); }}>{t(['PENDING', 'RUNNING'].includes(r.status) ? 'workList.follow' : r.status === 'COMPLETED' ? 'workList.result' : r.status === 'FAILED' ? 'workList.inspect' : 'workList.open')}</Button>}
            />)}</WorkList>}
          </ListFeedback>

          {/* Detail */}
          {selected && (
            <DetailPanel title={`${selected.modelA} vs ${selected.modelB}`} onClose={closeReport}
              summary={<div className="flex flex-wrap gap-3"><StatusBadge status={selected.status} /><span className="text-sm break-all">{selected.abId}</span></div>}
              history={<dl className="text-sm space-y-2"><dt>{t('detail.started')}</dt><dd>{new Date(selected.startedAt).toLocaleString(i18n.language)}</dd><dt>{t('detail.finished')}</dt><dd>{selected.completedAt ? new Date(selected.completedAt).toLocaleString(i18n.language) : '—'}</dd></dl>}
              actions={<Button variant="outline" onClick={closeReport}>{t('detail.close')}</Button>}
              details={<div className="space-y-6">
              <div className="bg-surface-container p-6 space-y-4">
                <div className="flex items-center justify-between">
                  <p className="font-headline font-bold text-lg">
                    <span className="text-primary">{selected.modelA}</span>
                    <span className="text-on-surface-variant mx-2">{t('ab.vs')}</span>
                    <span className="text-secondary">{selected.modelB}</span>
                  </p>
                  <span className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
                    {t('ab.judge', { name: selected.judgeModel })}
                  </span>
                </div>

                {selected.status === 'FAILED' && selected.error && (
                  <p className="text-xs text-error bg-error/10 px-3 py-2">{selected.error}</p>
                )}

                {selected.processed > 0 && (
                  <>
                    <WinBar a={selected.winRateA} t={selected.tieRate} b={selected.winRateB} />
                    <div className="flex items-center justify-between text-xs">
                      <span className="text-primary font-bold">A · {selected.aWins} ({Math.round(selected.winRateA * 100)}%)</span>
                      <span className="text-on-surface-variant">{t('ab.ties', { count: selected.ties })}</span>
                      <span className="text-secondary font-bold">{Math.round(selected.winRateB * 100)}% ({selected.bWins}) · B</span>
                    </div>
                    {selected.status !== 'COMPLETED' && (
                      <p className="font-label text-[11px] text-on-surface-variant text-right">
                        {t('ab.pairsJudged', { done: selected.processed, total: selected.testSetSize })}
                      </p>
                    )}
                  </>
                )}
              </div>

              {selected.items.length > 0 && (
                <div className="bg-surface-container">
                  <p className="px-4 py-3 font-label text-[11px] uppercase tracking-widest text-on-surface-variant bg-surface-container-high border-b border-outline-variant/10">
                    {t('ab.verdicts', { count: selected.items.length })}
                  </p>
                  {selected.items.map((it, i) => (
                    <AbItemRow key={i} item={it} modelA={selected.modelA} modelB={selected.modelB} />
                  ))}
                </div>
              )}
            </div>} />
          )}
        </div>
      )}
    </div>
  );
};

export default AbComparisonView;
