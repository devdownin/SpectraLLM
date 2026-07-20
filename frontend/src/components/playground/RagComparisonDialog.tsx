import { useState, useEffect } from 'react';
import type { FC } from 'react';
import { toast } from 'sonner';
import ChatMarkdown from '../ChatMarkdown';
import { abPreferencePair } from '../../lib/ragPipeline';
import type { ModuleDef } from '../../lib/ragPipeline';
import { dpoApi, queryApi } from '../../services/api';
import type { RagOverridesDto, StreamStageInfo } from '../../services/api';
import { useFocusTrap } from '../../hooks/useFocusTrap';
import type { Message, RagMeta, Source } from './ragTypes';
import { STRATEGY_COLORS, STAGE_LABELS } from './ragTypes';

/** Résumé compact du pipeline d'une réponse (badges + chunks) pour la comparaison A/B. */
const PipelineSummary: FC<{ meta?: RagMeta; sourceCount: number }> = ({ meta, sourceCount }) => {
  if (!meta) return null;
  const flags = [
    meta.hybridSearchApplied && 'HYB', meta.rerankApplied && 'RRNK', meta.multiQueryApplied && 'MQ',
    meta.correctiveApplied && 'CORR', meta.compressionApplied && 'CMPR', meta.selfRagApplied && 'SELF',
    meta.semanticDedupApplied && 'DEDUP',
  ].filter(Boolean) as string[];
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-[10px]">
      <span className={`font-bold px-1.5 py-0.5 border uppercase tracking-wider ${STRATEGY_COLORS[meta.ragStrategy] ?? STRATEGY_COLORS.STANDARD}`}>{meta.ragStrategy}</span>
      {flags.map(f => <span key={f} className="font-bold px-1.5 py-0.5 border border-primary/30 text-primary bg-primary/5 uppercase tracking-wider">{f}</span>)}
      <span className="text-outline font-mono ml-1">{typeof meta.chunkCount === 'number' ? meta.chunkCount : sourceCount} chunks</span>
    </div>
  );
};

/**
 * Comparaison A/B (chantier 4) : rejoue la même question avec UN module désactivé et présente
 * la réponse de référence (gauche) face à la variante streamée en direct (droite). Montre
 * concrètement l'apport du module sur la question que l'utilisateur vient de poser.
 *
 * Chargé à la demande (React.lazy) : n'entre dans le bundle que lorsque l'utilisateur ouvre
 * une comparaison, allégeant le chunk initial du Playground.
 */
export interface ComparisonProps {
  baseline: Message;
  question: string;
  module: ModuleDef;
  history: { role: string; content: string }[];
  temperature: number;
  topP: number;
  topCandidates: number;
  ragEnabled: boolean;
  baseOverrides?: RagOverridesDto;
  onClose: () => void;
}

const RagComparisonDialog: FC<ComparisonProps> = ({
  baseline, question, module, history, temperature, topP, topCandidates, ragEnabled, baseOverrides, onClose,
}) => {
  const [content, setContent] = useState('');
  const [sources, setSources] = useState<Source[]>([]);
  const [meta, setMeta] = useState<RagMeta | undefined>();
  const [stage, setStage] = useState<string | null>('Starting…');
  const [status, setStatus] = useState<'streaming' | 'done' | 'error'>('streaming');
  /** Préférence enregistrée en paire DPO (null tant que non votée). */
  const [preferred, setPreferred] = useState<'baseline' | 'variant' | null>(null);
  const panelRef = useFocusTrap<HTMLDivElement>(true, onClose);

  /** Enregistre la préférence (choix humain) comme paire DPO chosen/rejected. */
  const savePreference = (side: 'baseline' | 'variant') => {
    if (preferred) return;
    const pref = abPreferencePair(side, baseline.content, content, question, module.key);
    if (!pref) return;
    setPreferred(side);
    dpoApi.recordPreference(pref)
      .then(() => toast.success('Preference saved to the DPO dataset'))
      .catch(() => { setPreferred(null); toast.error('Could not save preference'); });
  };

  useEffect(() => {
    const controller = new AbortController();
    const overrides: RagOverridesDto = { ...(baseOverrides ?? {}), [module.key]: false };
    // Batching identique au chat principal (setTimeout ~80 ms) : évite un re-parse Markdown
    // par token, et flush déterministe (le rAF ne se déclenche pas de façon fiable hors
    // compositing, ce qui laissait la variante vide).
    let buf = '';
    let timer: ReturnType<typeof setTimeout> | null = null;
    const flush = () => {
      if (timer) { clearTimeout(timer); timer = null; }
      if (!buf) return;
      const chunk = buf; buf = '';
      setContent(c => c + chunk);
    };

    (async () => {
      try {
        for await (const ev of queryApi.queryStream(
          question, ragEnabled, controller.signal, topCandidates, history, temperature, topP, overrides
        )) {
          if (ev.type === 'sources') { try { setSources(JSON.parse(ev.data)); } catch { /* ignore */ } }
          else if (ev.type === 'token') { setStage(null); buf += ev.data; if (!timer) timer = setTimeout(flush, 80); }
          else if (ev.type === 'replace') { if (timer) { clearTimeout(timer); timer = null; } buf = ''; setContent(''); }
          else if (ev.type === 'stage') {
            try {
              const s = JSON.parse(ev.data) as StreamStageInfo;
              setStage(s.stage === 'agentic_search' ? `Agentic search #${s.iteration ?? '?'}` : STAGE_LABELS[s.stage] ?? s.stage);
            } catch { /* ignore */ }
          } else if (ev.type === 'done') {
            flush();
            try { setMeta(JSON.parse(ev.data) as RagMeta); } catch { /* ignore */ }
            setStatus('done');
          } else if (ev.type === 'error') {
            flush();
            setStatus('error');
          }
        }
        flush();
        setStatus(s => s === 'streaming' ? 'done' : s);
      } catch (err) {
        flush();
        if (!(err instanceof Error && err.name === 'AbortError')) setStatus('error');
      }
    })();

    return () => { controller.abort(); if (timer) clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-black/60 backdrop-blur-sm animate-in fade-in duration-300">
      <div ref={panelRef} tabIndex={-1} role="dialog" aria-modal="true" aria-label={`Comparison without ${module.label}`}
        className="bg-surface-container border border-outline-variant/30 shadow-2xl w-full max-w-5xl max-h-full flex flex-col rounded-xl overflow-hidden outline-none animate-in zoom-in-95 duration-300">
        <header className="px-6 py-4 border-b border-outline-variant/10 flex items-center justify-between bg-surface-container-high shrink-0">
          <div className="flex items-center gap-3 min-w-0">
            <div className="w-8 h-8 rounded-lg bg-primary/20 text-primary flex items-center justify-center shrink-0">
              <span className="material-symbols-outlined text-sm">compare_arrows</span>
            </div>
            <div className="min-w-0">
              <h2 className="font-headline font-bold text-lg text-on-surface truncate">A/B — without {module.label}</h2>
              <p className="text-[11px] uppercase tracking-widest text-on-surface-variant truncate">“{question}”</p>
            </div>
          </div>
          <button onClick={onClose} aria-label="Close comparison"
            className="w-8 h-8 flex items-center justify-center text-on-surface-variant hover:text-on-surface hover:bg-surface-container-highest transition-colors rounded-full shrink-0">
            <span className="material-symbols-outlined text-sm">close</span>
          </button>
        </header>

        <div className="flex-1 overflow-y-auto grid grid-cols-1 md:grid-cols-2 divide-y md:divide-y-0 md:divide-x divide-outline-variant/10 custom-scrollbar">
          {/* Référence */}
          <section className="p-5 space-y-3">
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-bold uppercase tracking-widest text-primary px-1.5 py-0.5 border border-primary/30 bg-primary/5">Current pipeline</span>
              <span className="text-[10px] uppercase tracking-widest text-outline">baseline</span>
            </div>
            <PipelineSummary meta={baseline.ragMeta} sourceCount={baseline.sources?.length ?? 0} />
            <div className="text-sm"><ChatMarkdown content={baseline.content} /></div>
            {baseline.sources && baseline.sources.length > 0 && (
              <p className="text-[10px] uppercase tracking-widest text-outline pt-2 border-t border-outline-variant/10">
                {baseline.sources.length} source(s): {baseline.sources.map(s => s.sourceFile).join(', ')}
              </p>
            )}
          </section>

          {/* Variante */}
          <section className="p-5 space-y-3">
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-bold uppercase tracking-widest text-error px-1.5 py-0.5 border border-error/30 bg-error/5">Without {module.label}</span>
              {status === 'streaming' && <span className="material-symbols-outlined text-[13px] text-outline animate-spin">progress_activity</span>}
            </div>
            {status === 'error' ? (
              <p className="text-sm text-error">The variant query failed. Try again.</p>
            ) : (
              <>
                <PipelineSummary meta={meta} sourceCount={sources.length} />
                <div className="text-sm">
                  {content ? <ChatMarkdown content={content} /> : (
                    <p className="text-[11px] uppercase tracking-widest text-outline flex items-center gap-1.5">
                      {stage && <span className="material-symbols-outlined text-[12px] animate-spin">progress_activity</span>}
                      {stage ?? 'Generating…'}
                    </p>
                  )}
                  {status === 'streaming' && content && <span className="inline-block w-1.5 h-3.5 bg-primary ml-0.5 animate-pulse align-middle" />}
                </div>
                {sources.length > 0 && (
                  <p className="text-[10px] uppercase tracking-widest text-outline pt-2 border-t border-outline-variant/10">
                    {sources.length} source(s): {sources.map(s => s.sourceFile).join(', ')}
                  </p>
                )}
              </>
            )}
          </section>
        </div>
        <footer className="px-6 py-3 border-t border-outline-variant/10 bg-surface-container-high shrink-0 space-y-2">
          {/* Vote de préférence → paire DPO (chosen/rejected) : le choix humain nourrit le fine-tuning. */}
          {status === 'done' && content.trim() && (
            preferred ? (
              <p className="text-[11px] text-center text-primary flex items-center justify-center gap-1.5">
                <span aria-hidden="true" className="material-symbols-outlined text-[14px]">check_circle</span>
                Preference saved — {preferred === 'baseline' ? 'current pipeline' : `without ${module.label}`} kept as a DPO pair.
              </p>
            ) : (
              <div className="flex items-center justify-center gap-2">
                <span className="text-[10px] uppercase tracking-widest text-outline">Which is better?</span>
                <button type="button" onClick={() => savePreference('baseline')}
                  className="flex items-center gap-1 text-[11px] uppercase tracking-widest border border-primary/40 text-primary px-2.5 py-1 hover:bg-primary/10 transition-colors">
                  <span aria-hidden="true" className="material-symbols-outlined text-[13px]">arrow_back</span>Current
                </button>
                <button type="button" onClick={() => savePreference('variant')}
                  className="flex items-center gap-1 text-[11px] uppercase tracking-widest border border-secondary/40 text-secondary px-2.5 py-1 hover:bg-secondary/10 transition-colors">
                  Without {module.label}<span aria-hidden="true" className="material-symbols-outlined text-[13px]">arrow_forward</span>
                </button>
              </div>
            )
          )}
          <p className="text-[10px] text-on-surface-variant text-center">
            Both answers use the same question, history and sampling — only <span className="font-bold text-error">{module.label}</span> differs.
          </p>
        </footer>
      </div>
    </div>
  );
};

export default RagComparisonDialog;
