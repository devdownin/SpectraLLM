import { useMemo } from 'react';
import type { FC } from 'react';
import { useQuery } from '@tanstack/react-query';
import { gedApi, ingestApi } from '../services/api';
import Skeleton from './Skeleton';
import Tooltip from './Tooltip';
import { useFocusTrap } from '../hooks/useFocusTrap';

// ── Strategy definitions ──────────────────────────────────────────────────────

interface StrategyMeta {
  key: string;
  name: string;
  tagline: string;
  icon: string;
  accentClass: string;
  envVar: string;
  description: string;
  tradeoff: string;
  bestFor: string;
}

const STRATEGIES: StrategyMeta[] = [
  {
    key: 'conversational',
    name: 'Conversational RAG',
    tagline: 'Multi-turn chat',
    icon: 'forum',
    accentClass: 'border-secondary/40 text-secondary bg-secondary/5',
    envVar: 'SPECTRA_CONVERSATIONAL_RAG_ENABLED=true',
    description:
      "Rewrites the current question into a standalone query using the conversation history before performing retrieval. The LLM also receives the full history during generation.",
    tradeoff: '+1 LLM call (question rewriting)',
    bestFor: "Chat interfaces with follow-up questions (\"and for him?\", \"when?\"). Unnecessary if questions are always independent.",
  },
  {
    key: 'corrective',
    name: 'Corrective RAG',
    tagline: 'Noisy data / imperfect index',
    icon: 'fact_check',
    accentClass: 'border-error/40 text-error bg-error/5',
    envVar: 'SPECTRA_CORRECTIVE_RAG_ENABLED=true',
    description:
      "Evaluates the relevance of each retrieved chunk (RELEVANT / AMBIGUOUS / IRRELEVANT) in a single batched LLM call. Off-topic chunks are filtered out before generation, which reduces hallucinations caused by noise.",
    tradeoff: '+1 batched LLM call (N chunks evaluated at once)',
    bestFor: "Average document quality score below 60%. Imperfect OCR, poorly structured PDFs, raw database exports.",
  },
  {
    key: 'adaptive',
    name: 'Adaptive RAG',
    tagline: 'Varied workloads / cost control',
    icon: 'route',
    accentClass: 'border-primary/40 text-primary bg-primary/5',
    envVar: 'SPECTRA_ADAPTIVE_RAG_ENABLED=true',
    description:
      "Classifies each query (DIRECT / STANDARD / AGENTIC) before routing it to the optimal strategy. General questions skip retrieval entirely. Complex questions trigger Agentic RAG.",
    tradeoff: '+1 short LLM call (classification) — saves retrieval on DIRECT queries',
    bestFor: "Large and varied corpus, mixing factual and general questions. Can halve latency on simple queries.",
  },
  {
    key: 'self_rag',
    name: 'Self-RAG',
    tagline: 'High reliability / zero hallucination',
    icon: 'verified',
    accentClass: 'border-primary/30 text-primary bg-primary/5',
    envVar: 'SPECTRA_SELF_RAG_ENABLED=true',
    description:
      "Self-assesses the generated answer using three reflection tokens: ISREL (relevant chunks?), ISSUP (answer grounded in sources?), ISUSE (answer useful?). If quality is insufficient, it re-runs generation with a reinforced prompt.",
    tradeoff: '+1 to +2 LLM calls (evaluation + optional refinement)',
    bestFor: "Critical documents (legal, medical, regulatory, audit) where factual errors are unacceptable.",
  },
  {
    key: 'agentic',
    name: 'Agentic RAG',
    tagline: 'Complex / multi-step questions',
    icon: 'psychology',
    accentClass: 'border-secondary/30 text-secondary bg-secondary/5',
    envVar: 'SPECTRA_AGENTIC_RAG_ENABLED=true',
    description:
      "ReAct reasoning loop: the LLM decides on its own whether it needs more information and reformulates its search queries iteratively (up to 3 rounds). Compatible with Hybrid Search and Re-ranking.",
    tradeoff: '+2 to +6 LLM calls (ReAct loop, 3 iterations max)',
    bestFor: "Analytical, comparative or multi-document questions. Large corpus with information spread across multiple sources.",
  },
];

// ── Recommendation engine ─────────────────────────────────────────────────────

type Priority = 'PRIORITY' | 'RECOMMENDED' | 'OPTIONAL';

interface StrategyRec {
  key: string;
  priority: Priority;
  reason: string;
}

const PRIORITY_STYLES: Record<Priority, string> = {
  PRIORITY:    'border-error/40 text-error bg-error/5',
  RECOMMENDED: 'border-primary/40 text-primary bg-primary/5',
  OPTIONAL:    'border-outline-variant/30 text-outline',
};

function computeRecommendations(
  stats: Record<string, any> | null,
  formats: string[],
): StrategyRec[] {
  if (!stats || stats.total === 0) return [];

  const total      = stats.total       as number ?? 0;
  const avgQ       = (stats.avgQualityScore as number | null) ?? null;
  const totalChunks = stats.totalChunks as number ?? 0;
  const qualDist   = stats.qualityDistribution as Record<string, number> ?? {};
  const lowQCount  = (qualDist['0.00-0.25'] ?? 0) + (qualDist['0.25-0.50'] ?? 0);

  const recs: StrategyRec[] = [];

  // ── Conversational : toujours utile dans le chat ────────────────────────────
  recs.push({
    key: 'conversational',
    priority: 'RECOMMENDED',
    reason: "Chat interface detected — improves retrieval on follow-up questions.",
  });

  // ── Corrective : données bruitées ──────────────────────────────────────────
  if (avgQ !== null && avgQ < 0.65) {
    recs.push({
      key: 'corrective',
      priority: avgQ < 0.45 ? 'PRIORITY' : 'RECOMMENDED',
      reason: `Average quality score ${(avgQ * 100).toFixed(0)}% — ${lowQCount} document(s) below 50%. Corrective filtering will reduce hallucinations.`,
    });
  }

  // ── Adaptive : corpus large ou varié ─────────────────────────────────────
  const formatVariety = formats.length;
  if (total >= 15 || formatVariety >= 3) {
    recs.push({
      key: 'adaptive',
      priority: total >= 50 ? 'PRIORITY' : 'RECOMMENDED',
      reason: `${total} document(s) • ${formatVariety} format(s) — adaptive routing optimizes latency and cost based on question complexity.`,
    });
  }

  // ── Self-RAG : corpus de qualité mais fiabilité requise ──────────────────
  if (avgQ !== null && avgQ >= 0.65 && totalChunks >= 100) {
    recs.push({
      key: 'self_rag',
      priority: 'RECOMMENDED',
      reason: `High-quality corpus (${(avgQ * 100).toFixed(0)}%) with ${totalChunks} chunks — self-assessment maximizes answer reliability.`,
    });
  }

  // ── Agentic : corpus volumineux ───────────────────────────────────────────
  if (totalChunks >= 500) {
    recs.push({
      key: 'agentic',
      priority: totalChunks >= 2000 ? 'PRIORITY' : 'RECOMMENDED',
      reason: `${totalChunks} indexed chunks — multi-step ReAct search better covers complex questions requiring multiple sources.`,
    });
  }

  // Deduplication and sorting
  const seen = new Set<string>();
  const order: Record<Priority, number> = { PRIORITY: 0, RECOMMENDED: 1, OPTIONAL: 2 };
  return recs
    .filter(r => { if (seen.has(r.key)) return false; seen.add(r.key); return true; })
    .sort((a, b) => order[a.priority] - order[b.priority]);
}

// ── Corpus profile label ──────────────────────────────────────────────────────

function corpusLabel(total: number, totalChunks: number): string {
  if (total === 0)       return 'No documents indexed';
  if (total <= 5)        return 'Minimal corpus';
  if (total <= 20)       return 'Light corpus';
  if (total <= 100)      return 'Moderate corpus';
  if (totalChunks >= 2000) return 'Large corpus';
  return 'Substantial corpus';
}

// ── Component ─────────────────────────────────────────────────────────────────

interface Props {
  open: boolean;
  onClose: () => void;
}

const RagAdvisor: FC<Props> = ({ open, onClose }) => {
  const { data: stats, isLoading: loadingStats } = useQuery<Record<string, any>>({
    queryKey: ['ged-stats-advisor'],
    queryFn: () => gedApi.getStats().then(r => r.data),
    enabled: open,
    staleTime: 30_000,
  });

  const { data: files } = useQuery<any[]>({
    queryKey: ['ingest-files-advisor'],
    queryFn: () => ingestApi.getHistory().then(r => r.data),
    enabled: open,
    staleTime: 30_000,
  });

  const formats = useMemo(() => {
    if (!files) return [];
    const seen = new Set<string>();
    files.forEach((f: any) => {
      const ext = (f.fileName as string).split('.').pop()?.toLowerCase() ?? f.format;
      if (ext) seen.add(ext);
    });
    return Array.from(seen);
  }, [files]);

  const recommendations = useMemo(() => computeRecommendations(stats ?? null, formats), [stats, formats]);

  // Focus trap + Esc to close + focus restoration.
  const panelRef = useFocusTrap<HTMLDivElement>(open, onClose);

  const total       = stats?.total       as number ?? 0;
  const avgQ        = stats?.avgQualityScore as number | null ?? null;
  const totalChunks = stats?.totalChunks  as number ?? 0;
  const qualDist    = stats?.qualityDistribution as Record<string, number> ?? {};

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/60 backdrop-blur-sm z-40 animate-in fade-in duration-200"
        onClick={onClose}
      />

      {/* Panel */}
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label="RAG Advisor"
        className="fixed inset-y-0 right-0 w-full lg:w-[560px] bg-surface-container-high shadow-[-20px_0_40px_rgba(0,0,0,0.5)] z-50 animate-in slide-in-from-right duration-300 border-l border-outline-variant/20 flex flex-col outline-none">

        {/* Header */}
        <header className="p-6 border-b border-outline-variant/20 flex justify-between items-start shrink-0">
          <div>
            <p className="text-[9px] font-label uppercase tracking-widest text-outline mb-1">Intelligence Pipeline</p>
            <h3 className="font-headline text-xl font-bold tracking-tight uppercase">RAG Advisor</h3>
            <p className="text-[10px] text-on-surface-variant mt-1">
              Recommendations based on your current corpus
            </p>
          </div>
          <button onClick={onClose} aria-label="Close RAG Advisor" className="w-10 h-10 flex items-center justify-center hover:bg-surface-variant transition-colors shrink-0 mt-1">
            <span aria-hidden="true" className="material-symbols-outlined">close</span>
          </button>
        </header>

        <div className="flex-1 overflow-y-auto custom-scrollbar">

          {/* ── Corpus analysis ───────────────────────────────────────────── */}
          <section className="p-6 border-b border-outline-variant/10">
            <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-4">Corpus analysis</p>

            {loadingStats ? (
              <div className="space-y-3">
                <Skeleton className="h-16" />
                <Skeleton className="h-10" />
              </div>
            ) : total === 0 ? (
              <div className="flex items-center gap-3 p-4 border border-outline-variant/20 bg-surface-container-lowest">
                <span className="material-symbols-outlined text-outline text-2xl">inbox</span>
                <div>
                  <p className="font-headline font-bold text-sm">No documents indexed</p>
                  <p className="text-[10px] text-on-surface-variant mt-0.5">Ingest documents from the <strong>GED / Database</strong> page to get recommendations.</p>
                </div>
              </div>
            ) : (
              <>
                {/* Stats cards */}
                <div className="grid grid-cols-3 gap-3 mb-4">
                  <div className="p-3 bg-surface-container-lowest border-l-2 border-primary">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Documents</p>
                    <p className="font-headline font-bold text-2xl">{total}</p>
                    <p className="text-[8px] text-on-surface-variant mt-0.5">{corpusLabel(total, totalChunks)}</p>
                  </div>
                  <div className="p-3 bg-surface-container-lowest border-l-2 border-secondary">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Avg. quality</p>
                    <p className={`font-headline font-bold text-2xl ${avgQ !== null && avgQ < 0.5 ? 'text-error' : avgQ !== null && avgQ >= 0.7 ? 'text-primary' : ''}`}>
                      {avgQ !== null ? `${(avgQ * 100).toFixed(0)}%` : '—'}
                    </p>
                    <p className="text-[8px] text-on-surface-variant mt-0.5">
                      {avgQ !== null && avgQ < 0.5 ? 'Noisy index' : avgQ !== null && avgQ >= 0.7 ? 'Good quality' : 'Average quality'}
                    </p>
                  </div>
                  <div className="p-3 bg-surface-container-lowest border-l-2 border-outline-variant">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-1">Chunks</p>
                    <p className="font-headline font-bold text-2xl">{totalChunks.toLocaleString('en-US')}</p>
                    <p className="text-[8px] text-on-surface-variant mt-0.5">{formats.length} format(s)</p>
                  </div>
                </div>

                {/* Quality histogram */}
                {Object.keys(qualDist).length > 0 && (
                  <div className="mb-4">
                    <p className="text-[8px] uppercase tracking-widest text-outline mb-2">Quality distribution</p>
                    <div className="flex gap-1 h-6">
                      {[
                        { key: '0.00-0.25', color: 'bg-error/60',            label: '0–25%' },
                        { key: '0.25-0.50', color: 'bg-error/30',            label: '25–50%' },
                        { key: '0.50-0.75', color: 'bg-secondary/40',        label: '50–75%' },
                        { key: '0.75-1.00', color: 'bg-primary/60',          label: '75–100%' },
                      ].map(({ key, color, label }) => {
                        const count = qualDist[key] ?? 0;
                        const pct = total > 0 ? (count / total) * 100 : 0;
                        return pct > 0 ? (
                          <Tooltip key={key} content={`${label}: ${count} doc(s)`}>
                            <div style={{ width: `${pct}%` }} className={`${color} h-full min-w-[4px] rounded-sm`} />
                          </Tooltip>
                        ) : null;
                      })}
                    </div>
                  </div>
                )}

                {/* Formats */}
                {formats.length > 0 && (
                  <div className="flex flex-wrap gap-1.5">
                    {formats.map(f => (
                      <span key={f} className="text-[8px] border border-outline-variant/30 px-1.5 py-0.5 uppercase font-mono text-outline-variant">
                        .{f}
                      </span>
                    ))}
                  </div>
                )}
              </>
            )}
          </section>

          {/* ── Recommendations ───────────────────────────────────────────── */}
          {recommendations.length > 0 && (
            <section className="p-6 border-b border-outline-variant/10">
              <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-4">
                Recommendations for this corpus
              </p>
              <div className="space-y-3">
                {recommendations.map(rec => {
                  const meta = STRATEGIES.find(s => s.key === rec.key)!;
                  return (
                    <div key={rec.key} className={`p-4 border ${PRIORITY_STYLES[rec.priority]} space-y-2`}>
                      <div className="flex items-center justify-between gap-3">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="material-symbols-outlined text-[18px] shrink-0">{meta.icon}</span>
                          <p className="font-headline font-bold text-sm tracking-tight truncate">{meta.name}</p>
                        </div>
                        <span className={`text-[8px] font-bold px-1.5 py-0.5 border uppercase tracking-wider shrink-0 ${PRIORITY_STYLES[rec.priority]}`}>
                          {rec.priority}
                        </span>
                      </div>
                      <p className="text-[10px] text-on-surface-variant leading-relaxed">{rec.reason}</p>
                      <button
                        onClick={() => navigator.clipboard.writeText(meta.envVar)}
                        className="flex items-center gap-2 text-[9px] font-mono border border-outline-variant/20 px-2 py-1 hover:border-primary/40 hover:text-primary transition-colors group w-full"
                      >
                        <span className="flex-1 text-left truncate">{meta.envVar}</span>
                        <span className="material-symbols-outlined text-[12px] text-outline group-hover:text-primary transition-colors shrink-0">content_copy</span>
                      </button>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* ── Full strategy guide ───────────────────────────────────────── */}
          <section className="p-6">
            <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-4">
              Strategy guide
            </p>
            <div className="space-y-4">
              {STRATEGIES.map(s => {
                const isRecommended = recommendations.some(r => r.key === s.key);
                return (
                  <details key={s.key} className={`border ${isRecommended ? s.accentClass : 'border-outline-variant/20'} group`}>
                    <summary className="flex items-center gap-3 px-4 py-3 cursor-pointer list-none select-none hover:bg-surface-container-high/50 transition-colors">
                      <span className={`material-symbols-outlined text-[18px] shrink-0 ${isRecommended ? '' : 'text-on-surface-variant'}`}>
                        {s.icon}
                      </span>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="font-headline font-bold text-sm tracking-tight">{s.name}</p>
                          {isRecommended && (
                            <span className="text-[7px] font-bold px-1 py-0.5 border border-primary/30 text-primary uppercase tracking-wider bg-primary/5">
                              ✓ recommended
                            </span>
                          )}
                        </div>
                        <p className="text-[9px] uppercase tracking-widest text-on-surface-variant mt-0.5">{s.tagline}</p>
                      </div>
                      <span className="material-symbols-outlined text-[16px] text-on-surface-variant group-open:rotate-180 transition-transform shrink-0">
                        expand_more
                      </span>
                    </summary>

                    <div className="px-4 pb-4 pt-1 space-y-3 border-t border-outline-variant/20">
                      <p className="text-xs text-on-surface-variant leading-relaxed">{s.description}</p>

                      <div className="grid grid-cols-1 gap-2">
                        <div className="flex items-start gap-2">
                          <span className="material-symbols-outlined text-[13px] text-outline mt-0.5 shrink-0">speed</span>
                          <div>
                            <p className="text-[8px] uppercase tracking-widest text-outline mb-0.5">Overhead</p>
                            <p className="text-[10px] text-on-surface-variant">{s.tradeoff}</p>
                          </div>
                        </div>
                        <div className="flex items-start gap-2">
                          <span className="material-symbols-outlined text-[13px] text-outline mt-0.5 shrink-0">tips_and_updates</span>
                          <div>
                            <p className="text-[8px] uppercase tracking-widest text-outline mb-0.5">When to enable</p>
                            <p className="text-[10px] text-on-surface-variant">{s.bestFor}</p>
                          </div>
                        </div>
                      </div>

                      <button
                        onClick={() => navigator.clipboard.writeText(s.envVar)}
                        className="flex items-center gap-2 text-[9px] font-mono border border-outline-variant/20 px-2 py-1.5 hover:border-primary/40 hover:text-primary transition-colors group w-full"
                      >
                        <span className="flex-1 text-left">{s.envVar}</span>
                        <span className="material-symbols-outlined text-[12px] text-outline group-hover:text-primary transition-colors">content_copy</span>
                      </button>
                    </div>
                  </details>
                );
              })}
            </div>

            {/* Compatibility matrix */}
            <div className="mt-6 p-4 bg-surface-container-lowest border border-outline-variant/10">
              <p className="text-[9px] font-bold uppercase tracking-widest text-outline mb-3">
                Compatibility — combining modules
              </p>
              <div className="space-y-1.5 text-[9px] text-on-surface-variant">
                {[
                  { combo: 'Conversational + Adaptive', note: 'Optimal — rewrites the query before routing.' },
                  { combo: 'Corrective + Reranker',     note: 'Recommended — double relevance filtering.' },
                  { combo: 'Adaptive → Agentic',        note: 'The classifier activates Agentic when AGENTIC.' },
                  { combo: 'Self-RAG + Agentic',        note: 'Avoid — double LLM loop, high latency.' },
                  { combo: 'All enabled',               note: 'Not recommended in production — reserve for testing.' },
                ].map(({ combo, note }) => (
                  <div key={combo} className="flex items-start gap-2">
                    <span className="font-mono text-primary shrink-0">{combo}</span>
                    <span className="text-outline">—</span>
                    <span>{note}</span>
                  </div>
                ))}
              </div>
            </div>
          </section>
        </div>
      </div>
    </>
  );
};

export default RagAdvisor;
