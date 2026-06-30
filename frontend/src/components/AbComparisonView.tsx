import { useState } from 'react';
import type { FC } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { configApi, evaluationApi } from '../services/api';
import type { AbComparisonReport, AbItem } from '../types/api';
import Skeleton from './Skeleton';

interface RegisteredModel { id?: string; name?: string; type?: string; }

const WINNER_LABEL: Record<string, string> = { A: 'A wins', B: 'B wins', TIE: 'Tie' };

function WinBar({ a, t, b }: { a: number; t: number; b: number }) {
  const pct = (v: number) => `${Math.round(v * 100)}%`;
  return (
    <div className="flex h-3 w-full overflow-hidden bg-surface-container-low">
      <div className="bg-primary h-full" style={{ width: pct(a) }} title={`A ${pct(a)}`} />
      <div className="bg-outline-variant/40 h-full" style={{ width: pct(t) }} title={`Tie ${pct(t)}`} />
      <div className="bg-secondary h-full" style={{ width: pct(b) }} title={`B ${pct(b)}`} />
    </div>
  );
}

function AbItemRow({ item, modelA, modelB }: { item: AbItem; modelA: string; modelB: string }) {
  const [open, setOpen] = useState(false);
  const color = item.winner === 'A' ? 'text-primary' : item.winner === 'B' ? 'text-secondary' : 'text-on-surface-variant';
  return (
    <div className="border-b border-outline-variant/10 last:border-0">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full text-left px-4 py-3 flex items-center gap-3 hover:bg-surface-container-high/40 transition-colors"
        aria-expanded={open}
      >
        <span className={`font-label text-[9px] uppercase tracking-widest w-16 shrink-0 ${color}`}>
          {WINNER_LABEL[item.winner]}
        </span>
        <span className="text-xs text-on-surface truncate flex-1">{item.question}</span>
        <span className="text-[10px] text-on-surface-variant shrink-0">{open ? '▲' : '▼'}</span>
      </button>
      {open && (
        <div className="px-4 pb-4 grid grid-cols-1 md:grid-cols-2 gap-3">
          <div className={`p-3 rounded border ${item.winner === 'A' ? 'bg-primary/5 border-primary/30' : 'bg-surface-container-low border-outline-variant/10'}`}>
            <p className="font-label text-[9px] uppercase tracking-widest text-primary mb-1">A · {modelA}</p>
            <p className="text-xs text-on-surface leading-relaxed">{item.answerA}</p>
          </div>
          <div className={`p-3 rounded border ${item.winner === 'B' ? 'bg-secondary/5 border-secondary/30' : 'bg-surface-container-low border-outline-variant/10'}`}>
            <p className="font-label text-[9px] uppercase tracking-widest text-secondary mb-1">B · {modelB}</p>
            <p className="text-xs text-on-surface leading-relaxed">{item.answerB}</p>
          </div>
          <div className="md:col-span-2 bg-surface-container-low p-3 rounded border border-outline-variant/10">
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Reference</p>
            <p className="text-xs text-on-surface-variant leading-relaxed">{item.reference}</p>
          </div>
        </div>
      )}
    </div>
  );
}

/** Comparaison directe A/B (head-to-head) entre deux modèles. */
const AbComparisonView: FC = () => {
  const queryClient = useQueryClient();
  const [modelA, setModelA] = useState('');
  const [modelB, setModelB] = useState('');
  const [size, setSize] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const { data: models = [] } = useQuery({
    queryKey: ['config-models'],
    queryFn: async (): Promise<RegisteredModel[]> => {
      const data = (await configApi.getModels()).data ?? [];
      return (data as RegisteredModel[]).filter(m => (m.type ?? 'chat') === 'chat');
    },
  });

  const { data: reports = [], isLoading } = useQuery({
    queryKey: ['ab-reports'],
    queryFn: async (): Promise<AbComparisonReport[]> =>
      (((await evaluationApi.getAllAb()).data ?? []) as AbComparisonReport[])
        .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime()),
    refetchInterval: (query) => {
      const data = query.state.data as AbComparisonReport[] | undefined;
      return data?.some(r => r.status === 'RUNNING' || r.status === 'PENDING') ? 4000 : false;
    },
  });

  const selected = reports.find(r => r.abId === selectedId) ?? reports[0] ?? null;
  const nameOf = (m: RegisteredModel) => m.name ?? m.id ?? '';

  const run = async () => {
    if (!modelA || !modelB || modelA === modelB) return;
    setSubmitting(true);
    try {
      const parsed = size.trim() ? parseInt(size, 10) : undefined;
      const res = await evaluationApi.submitAb(modelA, modelB, Number.isFinite(parsed) ? parsed : undefined);
      setSelectedId(res.data?.abId ?? null);
      await queryClient.invalidateQueries({ queryKey: ['ab-reports'] });
      toast.success('A/B comparison started', { description: 'Generation runs per model, then a judge picks the winner per pair.' });
    } catch (err: any) {
      toast.error('Failed to start A/B comparison', { description: err?.response?.data?.message ?? err?.message });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Setup */}
      <div className="bg-surface-container p-6 space-y-4">
        <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">New head-to-head</p>
        <div className="grid grid-cols-1 md:grid-cols-[1fr_auto_1fr_auto_auto] gap-3 items-end">
          <label className="space-y-1">
            <span className="font-label text-[9px] uppercase tracking-widest text-primary">Model A</span>
            <select value={modelA} onChange={e => setModelA(e.target.value)}
              className="w-full bg-surface-container-low text-sm px-2 py-2 outline-none border border-outline-variant/20 focus:border-primary/50">
              <option value="">Select…</option>
              {models.map(m => <option key={nameOf(m)} value={nameOf(m)}>{nameOf(m)}</option>)}
            </select>
          </label>
          <span className="text-on-surface-variant text-xs pb-2 text-center font-headline">vs</span>
          <label className="space-y-1">
            <span className="font-label text-[9px] uppercase tracking-widest text-secondary">Model B</span>
            <select value={modelB} onChange={e => setModelB(e.target.value)}
              className="w-full bg-surface-container-low text-sm px-2 py-2 outline-none border border-outline-variant/20 focus:border-secondary/50">
              <option value="">Select…</option>
              {models.map(m => <option key={nameOf(m)} value={nameOf(m)}>{nameOf(m)}</option>)}
            </select>
          </label>
          <label className="space-y-1">
            <span className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant">Test size</span>
            <input type="number" min={1} placeholder="auto" value={size} onChange={e => setSize(e.target.value)}
              className="w-20 bg-surface-container-low text-sm px-2 py-2 outline-none border border-outline-variant/20 focus:border-primary/50" />
          </label>
          <button onClick={run} disabled={!modelA || !modelB || modelA === modelB || submitting}
            className="px-4 py-2 bg-primary text-on-primary font-label text-[11px] uppercase tracking-widest hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed transition-opacity">
            {submitting ? 'Starting…' : 'Run A/B'}
          </button>
        </div>
        {modelA && modelB && modelA === modelB && (
          <p className="text-xs text-error">Pick two different models.</p>
        )}
      </div>

      {isLoading ? (
        <Skeleton className="h-64" />
      ) : reports.length === 0 ? (
        <div className="bg-surface-container p-8 text-center text-sm text-on-surface-variant">
          No A/B comparisons yet. Pick two models above and run one.
        </div>
      ) : (
        <div className="grid grid-cols-[240px_1fr] gap-6 items-start">
          {/* History */}
          <div className="bg-surface-container divide-y divide-outline-variant/10">
            <p className="px-4 py-3 bg-surface-container-high font-label text-[10px] uppercase tracking-widest text-on-surface-variant">History</p>
            {reports.map(r => (
              <button key={r.abId} onClick={() => setSelectedId(r.abId)}
                className={`w-full text-left px-4 py-3 transition-colors hover:bg-surface-container-high/60 ${selected?.abId === r.abId ? 'bg-surface-container-high' : ''}`}>
                <p className="font-headline font-bold text-xs truncate">{r.modelA} <span className="text-on-surface-variant">vs</span> {r.modelB}</p>
                <p className="font-label text-[9px] text-on-surface-variant mt-0.5">
                  {new Date(r.startedAt).toLocaleString('en-US', { dateStyle: 'short', timeStyle: 'short' })} · {r.status}
                </p>
                {r.status === 'COMPLETED' && (
                  <p className="font-headline text-xs font-bold mt-1">{r.aWins}–{r.ties}–{r.bWins}</p>
                )}
              </button>
            ))}
          </div>

          {/* Detail */}
          {selected && (
            <div className="space-y-6">
              <div className="bg-surface-container p-6 space-y-4">
                <div className="flex items-center justify-between">
                  <p className="font-headline font-bold text-lg">
                    <span className="text-primary">{selected.modelA}</span>
                    <span className="text-on-surface-variant mx-2">vs</span>
                    <span className="text-secondary">{selected.modelB}</span>
                  </p>
                  <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
                    judge: {selected.judgeModel}
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
                      <span className="text-on-surface-variant">Ties · {selected.ties}</span>
                      <span className="text-secondary font-bold">{Math.round(selected.winRateB * 100)}% ({selected.bWins}) · B</span>
                    </div>
                    {selected.status !== 'COMPLETED' && (
                      <p className="font-label text-[10px] text-on-surface-variant text-right">
                        {selected.processed} / {selected.testSetSize} pairs judged…
                      </p>
                    )}
                  </>
                )}
              </div>

              {selected.items.length > 0 && (
                <div className="bg-surface-container">
                  <p className="px-4 py-3 font-label text-[10px] uppercase tracking-widest text-on-surface-variant bg-surface-container-high border-b border-outline-variant/10">
                    Per-pair verdicts ({selected.items.length})
                  </p>
                  {selected.items.map((it, i) => (
                    <AbItemRow key={i} item={it} modelA={selected.modelA} modelB={selected.modelB} />
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default AbComparisonView;
