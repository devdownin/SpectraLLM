import type { FC } from 'react';
import { CountUp, SpotlightCard } from '../ui';
import {
  relevancePct, isBm25Only, fmtMs, formatStageCounts, buildFunnel, tokenBudget,
} from '../../lib/ragPipeline';
import type { FunnelStep } from '../../lib/ragPipeline';
import type { StreamStageTrace } from '../../services/api';
import type { Message } from './ragTypes';

/** Libellés courts des étapes dans la timeline serveur du panneau Trace. */
const STAGE_TRACE_LABELS: Record<string, string> = {
  routing:     'Routing',
  rewriting:   'Query rewrite',
  retrieval:   'Retrieval',
  grading:     'Corrective grading',
  compression: 'Compression',
  agentic:     'Agentic loop',
  generation:  'Generation',
  reflection:  'Self-RAG reflection',
};

/**
 * Timeline serveur du pipeline (chantiers 1 & 2) : une barre par étape proportionnelle à sa
 * durée mesurée côté serveur, avec ses compteurs (chunks avant→après, itérations agentiques).
 * Répond à « où est parti le temps ? » là où le TTFT global ne le dit pas.
 */
const StageTimeline: FC<{ stages: StreamStageTrace[] }> = ({ stages }) => {
  const total = stages.reduce((s, x) => s + Math.max(0, x.durationMs), 0);
  const max = Math.max(1, ...stages.map(s => s.durationMs));
  return (
    <div className="space-y-2.5">
      {stages.map((s, i) => {
        const label = STAGE_TRACE_LABELS[s.stage] ?? s.stage;
        const pct = Math.max(2, Math.round((s.durationMs / max) * 100));
        const counts = formatStageCounts(s);
        return (
          <div key={i} className="space-y-1">
            <div className="flex items-center justify-between text-[11px] gap-3">
              <span className="font-bold text-on-surface shrink-0">{label}</span>
              <span className="flex items-center gap-2 font-mono text-outline min-w-0">
                {counts && <span className="text-secondary truncate">{counts}</span>}
                <span className="shrink-0">{fmtMs(s.durationMs)}</span>
              </span>
            </div>
            <div className="h-1.5 bg-surface-container rounded overflow-hidden">
              <div className="h-full bg-primary/60 rounded" style={{ width: `${pct}%` }} />
            </div>
            {s.detail && s.stage !== 'agentic' && (
              <p className="text-[10px] text-outline font-mono truncate">{s.detail}</p>
            )}
          </div>
        );
      })}
      <div className="flex justify-between pt-1 border-t border-outline-variant/10 text-[10px] uppercase tracking-widest text-outline">
        <span>Total pipeline (server)</span>
        <span className="font-mono">{fmtMs(total)}</span>
      </div>
    </div>
  );
};

/**
 * Entonnoir de récupération : combien de chunks ont été récupérés, puis combien chaque étape
 * filtrante (Corrective, Compression) en a retiré jusqu'au contexte final. Rend visible « où »
 * et « par quoi » les chunks disparaissent — là où la timeline ne montrait que les durées.
 */
const RetrievalFunnel: FC<{ steps: FunnelStep[] }> = ({ steps }) => {
  const max = Math.max(1, ...steps.map(s => s.count));
  return (
    <div className="space-y-2.5">
      {steps.map((s, i) => {
        const pct = Math.max(4, Math.round((s.count / max) * 100));
        return (
          <div key={i} className="space-y-1">
            <div className="flex items-center justify-between text-[11px] gap-3">
              <span className="font-bold text-on-surface shrink-0">{s.label}</span>
              <span className="flex items-center gap-2 font-mono text-outline">
                {s.dropped > 0 && <span className="text-error">−{s.dropped} dropped</span>}
                <span className="text-on-surface">{s.count} chunks</span>
              </span>
            </div>
            <div className="h-2 bg-surface-container rounded overflow-hidden">
              <div className={`h-full rounded ${i === 0 ? 'bg-primary/60' : 'bg-secondary/60'}`} style={{ width: `${pct}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
};

/**
 * Budget de tokens (estimé à ~4 caractères/token) : part du contexte récupéré (entrée) vs
 * réponse générée (sortie). Répond à « combien du budget est parti dans le contexte récupéré ? ».
 */
const TokenBudgetBar: FC<{ contextChars: number; outputTokens: number }> = ({ contextChars, outputTokens }) => {
  const b = tokenBudget(contextChars, outputTokens);
  if (b.total === 0) return null;
  return (
    <div className="space-y-2">
      <div className="flex h-3 rounded overflow-hidden bg-surface-container">
        <div className="bg-primary/60 h-full" style={{ width: `${b.inputPct}%` }} title={`Retrieved context — ~${b.inputTokens} tokens`} />
        <div className="bg-secondary/60 h-full" style={{ width: `${100 - b.inputPct}%` }} title={`Generated answer — ${b.outputTokens} tokens`} />
      </div>
      <div className="flex items-center justify-between text-[11px] font-mono">
        <span className="flex items-center gap-1.5"><span className="inline-block w-2 h-2 bg-primary/60 rounded-sm" />Context in ~{b.inputTokens} tok</span>
        <span className="flex items-center gap-1.5"><span className="inline-block w-2 h-2 bg-secondary/60 rounded-sm" />Answer out {b.outputTokens} tok</span>
      </div>
      <p className="text-[10px] text-outline">Estimated at ~4 characters/token. Context = the retrieved chunks injected into the prompt.</p>
    </div>
  );
};

/**
 * Panneau Trace : détail d'exécution d'une réponse (stratégie, timeline serveur, entonnoir,
 * budget de tokens, optimisations déclenchées, contexte final). Chargé à la demande (React.lazy) —
 * n'entre dans le bundle que lorsque l'utilisateur ouvre une trace.
 */
const RagTracePanel: FC<{ traceMsg: Message; onClose: () => void }> = ({ traceMsg, onClose }) => (
  <div className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-black/60 backdrop-blur-sm animate-in fade-in duration-300">
    <div className="bg-surface-container border border-outline-variant/30 shadow-2xl w-full max-w-4xl max-h-full flex flex-col rounded-xl overflow-hidden animate-in zoom-in-95 duration-300">
      <header className="px-6 py-4 border-b border-outline-variant/10 flex items-center justify-between bg-surface-container-high shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-lg bg-primary/20 text-primary flex items-center justify-center">
            <span className="material-symbols-outlined text-sm">insights</span>
          </div>
          <div>
            <h2 className="font-headline font-bold text-lg text-on-surface">Algorithm Trace</h2>
            <p className="text-[11px] uppercase tracking-widest text-on-surface-variant">Execution details for the selected response</p>
          </div>
        </div>
        <button
          onClick={onClose}
          className="w-8 h-8 flex items-center justify-center text-on-surface-variant hover:text-on-surface hover:bg-surface-container-highest transition-colors rounded-full"
          aria-label="Close trace panel"
        >
          <span className="material-symbols-outlined text-sm">close</span>
        </button>
      </header>

      <div className="flex-1 overflow-y-auto p-6 space-y-8 custom-scrollbar">
        <div className="space-y-3">
          <h3 className="font-headline font-bold text-sm text-primary uppercase tracking-widest flex items-center gap-2">
            <span className="material-symbols-outlined text-base">route</span>
            Strategy Applied
          </h3>
          <div className="bg-surface-container-low border border-outline-variant/10 rounded-lg p-4">
            <div className="flex flex-col md:flex-row gap-6 md:items-center justify-between">
              <div>
                 <p className="text-xl font-headline font-bold text-on-surface">{traceMsg.ragMeta?.ragStrategy}</p>
                 <p className="text-xs text-on-surface-variant mt-1">
                   {traceMsg.ragMeta?.ragStrategy === 'AGENTIC' ? "The LLM actively reasoned and queried the index in a loop to answer the question." :
                    traceMsg.ragMeta?.ragStrategy === 'STANDARD' ? "Standard retrieve-and-generate pipeline." :
                    "The question bypassed the index."}
                 </p>
              </div>
              {traceMsg.ragMeta?.ragStrategy === 'AGENTIC' && typeof traceMsg.ragMeta?.agenticIterations === 'number' && (
                <div className="text-right">
                  <p className="text-2xl font-mono text-primary"><CountUp to={traceMsg.ragMeta.agenticIterations} /></p>
                  <p className="text-[11px] uppercase tracking-widest text-on-surface-variant mt-1">Search iterations</p>
                  {traceMsg.ragMeta.agenticStopReason && (
                    <p className="text-[10px] font-mono text-outline mt-0.5" title="Why the ReAct loop stopped">
                      stop: {traceMsg.ragMeta.agenticStopReason}
                    </p>
                  )}
                </div>
              )}
              {typeof traceMsg.ragMeta?.chunkCount === 'number' && (
                <div className="text-right">
                  <p className="text-2xl font-mono text-primary"><CountUp to={traceMsg.ragMeta.chunkCount} /></p>
                  <p className="text-[11px] uppercase tracking-widest text-on-surface-variant mt-1">Context chunks</p>
                </div>
              )}
            </div>
          </div>
        </div>

        {traceMsg.ragMeta?.stages && traceMsg.ragMeta.stages.length > 0 && (
          <div className="space-y-3">
            <h3 className="font-headline font-bold text-sm text-primary uppercase tracking-widest flex items-center gap-2">
              <span className="material-symbols-outlined text-base">timeline</span>
              Pipeline Timeline
              <span className="text-[10px] normal-case tracking-normal font-body font-normal text-on-surface-variant">(server-measured)</span>
            </h3>
            <div className="bg-surface-container-low border border-outline-variant/10 rounded-lg p-4">
              <StageTimeline stages={traceMsg.ragMeta.stages} />
            </div>
          </div>
        )}

        {(() => {
          const funnel = traceMsg.ragMeta?.stages ? buildFunnel(traceMsg.ragMeta.stages) : [];
          return funnel.length > 1 ? (
            <div className="space-y-3">
              <h3 className="font-headline font-bold text-sm text-primary uppercase tracking-widest flex items-center gap-2">
                <span className="material-symbols-outlined text-base">filter_list</span>
                Retrieval Funnel
                <span className="text-[10px] normal-case tracking-normal font-body font-normal text-on-surface-variant">(chunks kept per stage)</span>
              </h3>
              <div className="bg-surface-container-low border border-outline-variant/10 rounded-lg p-4">
                <RetrievalFunnel steps={funnel} />
              </div>
            </div>
          ) : null;
        })()}

        {typeof traceMsg.ragMeta?.contextChars === 'number' && traceMsg.ragMeta.contextChars > 0 && (
          <div className="space-y-3">
            <h3 className="font-headline font-bold text-sm text-secondary uppercase tracking-widest flex items-center gap-2">
              <span className="material-symbols-outlined text-base">data_usage</span>
              Token Budget
              <span className="text-[10px] normal-case tracking-normal font-body font-normal text-on-surface-variant">(estimated)</span>
            </h3>
            <div className="bg-surface-container-low border border-outline-variant/10 rounded-lg p-4">
              <TokenBudgetBar contextChars={traceMsg.ragMeta.contextChars} outputTokens={traceMsg.metrics?.tokens ?? 0} />
            </div>
          </div>
        )}

        {traceMsg.ragMeta?.rewrittenQuestion && (
          <div className="space-y-3">
            <h3 className="font-headline font-bold text-sm text-secondary uppercase tracking-widest flex items-center gap-2">
              <span className="material-symbols-outlined text-base">edit_note</span>
              Query Rewriting (Conversational RAG)
            </h3>
            <div className="bg-surface-container-low border border-outline-variant/10 rounded-lg p-4">
              <p className="text-[10px] uppercase tracking-widest text-outline mb-1">Standalone question used for retrieval</p>
              <p className="text-sm text-on-surface leading-relaxed">“{traceMsg.ragMeta.rewrittenQuestion}”</p>
              <p className="text-[11px] text-on-surface-variant mt-2">
                The question was rephrased using the conversation history before searching the index.
              </p>
            </div>
          </div>
        )}

        <div className="space-y-3">
          <h3 className="font-headline font-bold text-sm text-secondary uppercase tracking-widest flex items-center gap-2">
            <span className="material-symbols-outlined text-base">filter_alt</span>
            Optimizations Triggered
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
             {[
                { active: traceMsg.ragMeta?.conversationalApplied, label: 'Conversational RAG', desc: 'Question rephrased into a standalone query using the conversation history.' },
                { active: traceMsg.ragMeta?.hybridSearchApplied, label: 'Hybrid Search', desc: 'Merged BM25 (exact text) + ChromaDB (semantic vectors) via Reciprocal Rank Fusion.' },
                { active: traceMsg.ragMeta?.rerankApplied, label: 'Cross-Encoder', desc: 'Candidates rescored jointly with the query by a Cross-Encoder for higher accuracy.' },
                { active: traceMsg.ragMeta?.multiQueryApplied, label: 'Multi-Query', desc: 'LLM generated query variations to broaden the search net.' },
                { active: traceMsg.ragMeta?.compressionApplied, label: 'Context Compression', desc: 'Extracted only the relevant sentences from large chunks.' },
                { active: traceMsg.ragMeta?.semanticDedupApplied, label: 'Semantic Dedup', desc: 'Filtered out near-duplicate chunks via Jaccard similarity.' },
                { active: traceMsg.ragMeta?.correctiveApplied, label: 'Corrective RAG', desc: 'LLM graded retrieved chunks and discarded irrelevant ones.' },
                { active: traceMsg.ragMeta?.longContextApplied, label: 'Long-Context RAG', desc: 'Small corpus loaded in full — vector retrieval skipped entirely.' },
                { active: traceMsg.ragMeta?.selfRagApplied, label: 'Self-RAG', desc: traceMsg.ragMeta?.selfRagScores
                    ? `Answer self-evaluated (ISREL/ISSUP/ISUSE: ${traceMsg.ragMeta.selfRagScores})${traceMsg.ragMeta.selfRagApplied ? ' and refined.' : ' — no refinement needed.'}`
                    : 'Answer self-evaluated via reflection tokens and refined when quality was insufficient.' },
             ].map(opt => (
               <SpotlightCard
                 key={opt.label}
                 spotlight={opt.active ? 'color-mix(in srgb, var(--color-secondary) 16%, transparent)' : undefined}
                 className={`p-4 rounded-lg border ${opt.active ? 'bg-secondary/10 border-secondary/30' : 'bg-surface-container-low border-outline-variant/10 opacity-50'}`}
               >
                  <div className="flex items-center gap-2 mb-2">
                    <span className={`material-symbols-outlined text-sm ${opt.active ? 'text-secondary' : 'text-on-surface-variant'}`}>
                      {opt.active ? 'check_circle' : 'cancel'}
                    </span>
                    <span className={`font-bold text-xs ${opt.active ? 'text-secondary' : 'text-on-surface-variant'}`}>{opt.label}</span>
                  </div>
                  <p className="text-[11px] text-on-surface-variant leading-relaxed">{opt.desc}</p>
               </SpotlightCard>
             ))}
          </div>
        </div>

        {traceMsg.sources && traceMsg.sources.length > 0 && (
          <div className="space-y-3">
            <h3 className="font-headline font-bold text-sm text-primary uppercase tracking-widest flex items-center gap-2">
              <span className="material-symbols-outlined text-base">format_list_numbered</span>
              Final Context Sent to the LLM
              <span className="text-[10px] normal-case tracking-normal font-body font-normal text-on-surface-variant">(source previews)</span>
            </h3>
            <div className="space-y-2">
               {traceMsg.sources.map((src, i) => {
                 const pct = relevancePct(src);
                 return (
                  <div key={i} className="bg-surface-container-low border border-outline-variant/10 p-3 rounded text-xs space-y-1">
                     <div className="flex items-center justify-between gap-2">
                       <span className="font-bold text-on-surface break-all">{src.sourceFile}</span>
                       <span className="flex items-center gap-1.5 shrink-0">
                         {pct !== null && (
                           <span className="text-[11px] bg-primary/20 text-primary px-1.5 rounded">{pct}% relevance</span>
                         )}
                         {isBm25Only(src) && (
                           <span className="text-[11px] bg-secondary/20 text-secondary px-1.5 rounded" title="Found by keyword search (BM25) — no vector distance available">BM25 match</span>
                         )}
                         {typeof src.rerankScore === 'number' && (
                           <span className="text-[11px] bg-surface-container-high text-on-surface-variant px-1.5 rounded font-mono" title="Cross-Encoder re-ranking score (higher = more relevant)">rr {src.rerankScore.toFixed(2)}</span>
                         )}
                       </span>
                     </div>
                     <p className="text-on-surface-variant line-clamp-2" title={src.text || src.preview}>{src.text || src.preview}</p>
                  </div>
                 );
               })}
            </div>
          </div>
        )}
      </div>
    </div>
  </div>
);

export default RagTracePanel;
