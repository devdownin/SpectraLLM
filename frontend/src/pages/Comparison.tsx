import { useState, useEffect } from 'react';
import type { FC } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { evaluationApi } from '../services/api';
import type { EvaluationReport, EvaluationScore } from '../types/api';
import ScoreRadar from '../components/charts/ScoreRadar';
import Skeleton from '../components/Skeleton';

const STATUS_LABEL: Record<string, string> = {
  PENDING:   'Pending',
  RUNNING:   'Running',
  COMPLETED: 'Completed',
  FAILED:    'Failed',
};

const STATUS_COLOR: Record<string, string> = {
  PENDING:   'text-on-surface-variant',
  RUNNING:   'text-secondary',
  COMPLETED: 'text-primary',
  FAILED:    'text-error',
};

const CATEGORY_LABEL: Record<string, string> = {
  qa:             'Q&A',
  summary:        'Summary',
  classification: 'Classification',
  negative:       'Negative',
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
      <span className="font-label text-[10px] text-on-surface-variant">{value}/{max}</span>
    </div>
  );
}

function ScoreDetail({ score }: { score: EvaluationScore }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="border-b border-outline-variant/10 last:border-0">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full text-left px-4 py-3 flex items-center gap-3 hover:bg-surface-container-high/40 transition-colors"
      >
        <span className="font-headline text-xs font-bold w-8 shrink-0"
              style={{ color: score.score >= 7 ? 'var(--color-primary)' : score.score >= 4 ? 'var(--color-secondary)' : 'var(--color-error)' }}>
          {score.score.toFixed(0)}/10
        </span>
        <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant w-20 shrink-0">
          {CATEGORY_LABEL[score.category] ?? score.category}
        </span>
        <span className="text-xs text-on-surface truncate flex-1">{score.question}</span>
        <span className="text-[10px] text-on-surface-variant shrink-0">{open ? '▲' : '▼'}</span>
      </button>
      {open && (
        <div className="px-4 pb-4 space-y-3 bg-surface-container-low/20">
          <div>
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Reference answer</p>
            <p className="text-xs text-on-surface-variant leading-relaxed">{score.referenceAnswer}</p>
          </div>
          <div>
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Model answer</p>
            <p className="text-xs text-on-surface leading-relaxed">{score.modelAnswer}</p>
          </div>
          <div>
            <p className="font-label text-[9px] uppercase tracking-widest text-on-surface-variant mb-1">Judge justification</p>
            <p className="text-xs italic text-on-surface-variant">{score.justification}</p>
          </div>
          <p className="font-label text-[9px] text-on-surface-variant">Source: {score.source}</p>
        </div>
      )}
    </div>
  );
}

const Comparison: FC = () => {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<EvaluationReport | null>(null);
  const [isTriggering, setIsTriggering] = useState(false);

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

  // Synchronise la sélection quand la liste change (préserve le rapport choisi).
  useEffect(() => {
    setSelected(prev =>
      prev ? (reports.find(r => r.evalId === prev.evalId) ?? reports[0] ?? null) : (reports[0] ?? null)
    );
  }, [reports]);

  const handleNewEvaluation = async () => {
    setIsTriggering(true);
    try {
      await evaluationApi.submit();
      await queryClient.invalidateQueries({ queryKey: ['evaluation-reports'] });
    } catch {
      // ignore
    } finally {
      setIsTriggering(false);
    }
  };

  const categories = selected ? Object.entries(selected.scoresByCategory) : [];

  return (
    <div className="space-y-8 animate-in fade-in duration-700">
      {/* Header */}
      <header className="flex items-end justify-between">
        <div>
          <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-1">
            LLM-as-a-Judge
          </p>
          <h2 className="font-headline text-3xl font-bold tracking-tighter">MODEL EVALUATION</h2>
        </div>
        <button
          onClick={handleNewEvaluation}
          disabled={isTriggering}
          className="px-4 py-2 bg-primary text-on-primary font-label text-[11px] uppercase tracking-widest
                     hover:opacity-90 disabled:opacity-50 disabled:cursor-not-allowed transition-opacity"
        >
          {isTriggering ? 'Launching...' : '+ New evaluation'}
        </button>
      </header>

      {isLoading ? (
        <div className="grid grid-cols-1 lg:grid-cols-[260px_1fr] gap-6 items-start">
          <Skeleton className="h-64" />
          <Skeleton className="h-80" />
        </div>
      ) : reports.length === 0 ? (
        <div className="bg-surface-container p-8 text-center space-y-2">
          <p className="font-headline text-lg">No evaluations</p>
          <p className="text-sm text-on-surface-variant">
            Click &quot;New evaluation&quot; to score the active model&apos;s quality
            on a dataset sample.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-[260px_1fr] gap-6 items-start">
          {/* Evaluation list */}
          <div className="bg-surface-container divide-y divide-outline-variant/10">
            <p className="px-4 py-3 font-label text-[10px] uppercase tracking-widest text-on-surface-variant bg-surface-container-high">History</p>
            {reports.map(r => (
              <button
                key={r.evalId}
                onClick={() => setSelected(r)}
                className={`w-full text-left px-4 py-3 transition-colors hover:bg-surface-container-high/60
                  ${selected?.evalId === r.evalId ? 'bg-surface-container-high' : ''}`}
              >
                <div className="flex items-center justify-between mb-0.5">
                  <span className="font-headline font-bold text-xs truncate pr-2">{r.modelName}</span>
                  <span className={`font-label text-[9px] uppercase tracking-widest shrink-0 ${STATUS_COLOR[r.status]}`}>
                    {STATUS_LABEL[r.status]}
                  </span>
                </div>
                <p className="font-label text-[9px] text-on-surface-variant">
                  {new Date(r.startedAt).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' })}
                </p>
                {r.status === 'COMPLETED' && (
                  <p className="font-headline text-sm font-bold mt-1">
                    {r.averageScore.toFixed(2)}<span className="text-[10px] font-normal text-on-surface-variant">/10</span>
                  </p>
                )}
                {r.status === 'RUNNING' && (
                  <ProgressBar value={r.processed} max={r.testSetSize} />
                )}
              </button>
            ))}
          </div>

          {/* Detail panel */}
          {selected && (
            <div className="space-y-6">
              {/* Overview */}
              <div className="bg-surface-container p-6 space-y-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Evaluated model</p>
                    <p className="font-headline font-bold text-lg">{selected.modelName}</p>
                  </div>
                  <span className={`font-label text-[11px] uppercase tracking-widest px-3 py-1 border ${STATUS_COLOR[selected.status]}
                    ${selected.status === 'COMPLETED' ? 'border-primary/30' : 'border-outline-variant/30'}`}>
                    {STATUS_LABEL[selected.status]}
                  </span>
                </div>

                {(selected.status === 'RUNNING' || selected.status === 'PENDING') && (
                  <div>
                    <div className="flex items-center justify-between mb-1">
                      <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Progression</p>
                      <span className="flex items-center gap-1 text-[9px] font-label uppercase tracking-widest text-secondary">
                        <span className="w-1.5 h-1.5 bg-secondary rounded-full animate-pulse inline-block" />
                        {selected.status === 'PENDING' ? 'Pending…' : 'Running'}
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
                      <p className="font-label text-[9px] text-on-surface-variant mt-0.5 text-right">
                        {selected.processed} / {selected.testSetSize} paires
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
                      <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-2">Score global</p>
                      <div className="flex items-baseline gap-2">
                        <span className="font-headline text-4xl font-bold">{selected.averageScore.toFixed(2)}</span>
                        <span className="text-on-surface-variant">/10</span>
                        <span className="font-label text-[10px] text-on-surface-variant ml-2">
                          sur {selected.processed} paire{selected.processed > 1 ? 's' : ''}
                        </span>
                      </div>
                    </div>

                    {categories.length > 0 && (
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 items-start">
                        {/* Score bars */}
                        <div>
                          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-3">By category</p>
                          <div className="space-y-2">
                            {categories.map(([cat, avg]) => (
                              <div key={cat} className="grid grid-cols-[100px_1fr] gap-3 items-center">
                                <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
                                  {CATEGORY_LABEL[cat] ?? cat}
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
                            <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant mb-1">Radar</p>
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
                  <p className="px-4 py-3 font-label text-[10px] uppercase tracking-widest text-on-surface-variant bg-surface-container-high border-b border-outline-variant/10">
                    Détail des paires ({selected.scores.length})
                  </p>
                  {selected.scores.map((s, i) => (
                    <ScoreDetail key={i} score={s} />
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

export default Comparison;
