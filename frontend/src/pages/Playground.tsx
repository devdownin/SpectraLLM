import { useState, useEffect, useRef } from 'react';
import type { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { queryApi, configApi, fineTuningApi, ingestApi } from '../services/api';
import type { StreamDoneMeta } from '../services/api';
import Tooltip from '../components/Tooltip';
import RagAdvisor from '../components/RagAdvisor';
import ChatMarkdown from '../components/ChatMarkdown';

interface Source {
  preview?: string;
  text?: string;
  sourceFile: string;
  distance: number;
}

interface RagMeta {
  conversationalApplied: boolean;
  correctiveApplied: boolean;
  selfRagApplied: boolean;
  ragStrategy: string;
  rerankApplied: boolean;
  hybridSearchApplied: boolean;
  multiQueryApplied: boolean;
  compressionApplied: boolean;
  semanticDedupApplied: boolean;
  longContextApplied: boolean;
}

interface MessageMetrics {
  ttftMs: number;   // time to first token
  totalMs: number;  // total generation time
  tokens: number;   // approximate token count
}

interface Message {
  role: 'user' | 'assistant';
  content: string;
  sources?: Source[];
  ragMeta?: RagMeta;
  status?: 'PENDING' | 'SENT' | 'ERROR' | 'STREAMING';
  metrics?: MessageMetrics;
  feedback?: 'UP' | 'DOWN';
  /** Réponse interrompue par l'utilisateur (Stop) — donc potentiellement incomplète. */
  stopped?: boolean;
}

const STRATEGY_COLORS: Record<string, string> = {
  DIRECT:   'border-secondary/40 text-secondary bg-secondary/5',
  STANDARD: 'border-outline-variant/30 text-outline',
  AGENTIC:  'border-primary/40 text-primary bg-primary/5',
};

const RagBadges: FC<{ meta: RagMeta }> = ({ meta }) => {
  const badges: { label: string; active: boolean; tooltip: string }[] = [
    { label: 'CONV',  active: meta.conversationalApplied, tooltip: 'Conversational RAG — question rephrased using conversation history' },
    { label: 'CORR',  active: meta.correctiveApplied,     tooltip: 'Corrective RAG — irrelevant chunks filtered out' },
    { label: 'SELF',  active: meta.selfRagApplied,        tooltip: 'Self-RAG — self-evaluated and refined answer' },
    { label: 'RRNK',  active: meta.rerankApplied,         tooltip: 'Cross-Encoder re-ranking applied' },
    { label: 'HYB',   active: meta.hybridSearchApplied,   tooltip: 'Hybrid Search (Vector + BM25) used' },
    { label: 'MQ',    active: meta.multiQueryApplied,     tooltip: 'Multi-Query — N question variants merged' },
    { label: 'CMPR',  active: meta.compressionApplied,    tooltip: 'Context Compression — relevant passages extracted' },
    { label: 'DEDUP', active: meta.semanticDedupApplied,  tooltip: 'Semantic Dedup — near-duplicate passages removed' },
    { label: 'FULL',  active: meta.longContextApplied,    tooltip: 'Long-Context RAG — full corpus loaded' },
  ];

  const activeBadges = badges.filter(b => b.active);
  if (activeBadges.length === 0 && meta.ragStrategy === 'STANDARD') return null;

  return (
    <div className="mt-3 pt-3 border-t border-outline-variant/20 flex flex-wrap items-center gap-1.5">
      <Tooltip content={`Strategy: ${meta.ragStrategy}`}>
        <span className={`text-[8px] font-bold px-1.5 py-0.5 border uppercase tracking-wider cursor-help ${STRATEGY_COLORS[meta.ragStrategy] ?? STRATEGY_COLORS.STANDARD}`}>
          {meta.ragStrategy}
        </span>
      </Tooltip>
      {activeBadges.map(b => (
        <Tooltip key={b.label} content={b.tooltip}>
          <span className="text-[8px] font-bold px-1.5 py-0.5 border border-primary/30 text-primary bg-primary/5 uppercase tracking-wider cursor-help">
            {b.label}
          </span>
        </Tooltip>
      ))}
    </div>
  );
};

/** Source de réponse dépliable : nom de fichier + pertinence + passage récupéré. */
const SourceItem: FC<{ src: Source }> = ({ src }) => {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const snippet = src.preview ?? src.text ?? '';
  const pct = typeof src.distance === 'number' ? Math.max(0, Math.min(100, Math.round((1 - src.distance) * 100))) : null;

  const openInDatabase = async () => {
    try {
      const res = await ingestApi.getHistory({ q: src.sourceFile, size: 5 });
      const items: any[] = res.data?.content ?? res.data ?? [];
      const match = items.find(d => d.fileName === src.sourceFile) ?? items[0];
      if (match?.sha256) navigate(`/pipelines?doc=${encodeURIComponent(match.sha256)}`);
      else toast.error('Document not found in the Database');
    } catch {
      toast.error('Could not open the document');
    }
  };

  return (
    <div className="border-b border-outline-variant/10 last:border-0">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        className="w-full flex items-center gap-2 py-1 text-left hover:text-primary transition-colors"
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[12px] text-primary shrink-0">article</span>
        <span className="font-mono text-[9px] text-on-surface-variant truncate flex-1">{src.sourceFile}</span>
        {pct !== null && <span className="text-[8px] font-bold text-primary shrink-0" title="Relevance">{pct}%</span>}
        <span aria-hidden="true" className={`material-symbols-outlined text-[12px] text-outline shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}>expand_more</span>
      </button>
      {open && (
        <div className="pl-5 pb-2 space-y-1.5">
          {snippet
            ? <p className="text-[10px] text-on-surface-variant leading-relaxed whitespace-pre-wrap">{snippet}</p>
            : <p className="text-[10px] text-outline italic">No preview available.</p>}
          <div className="flex items-center justify-between">
            <span className="text-[8px] font-mono text-outline">distance: {typeof src.distance === 'number' ? src.distance.toFixed(3) : '—'}</span>
            <button
              type="button"
              onClick={openInDatabase}
              className="flex items-center gap-1 text-[8px] uppercase tracking-widest text-primary hover:text-primary/70 transition-colors"
            >
              <span aria-hidden="true" className="material-symbols-outlined text-[11px]">open_in_new</span>Open in Database
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

const Playground: FC = () => {
  const defaultWelcome: Message = { role: 'assistant', content: 'Welcome to the Spectra Playground. I am ready to answer questions based on your ingested documents. How can I help you today?', status: 'SENT' };
  const [messages, setMessages] = useState<Message[]>(() => {
    const saved = localStorage.getItem('spectra_chat_history');
    if (!saved) return [defaultWelcome];
    try { return JSON.parse(saved); } catch { return [defaultWelcome]; }
  });
  const [input, setInput] = useState('');
  const [isTyping, setIsTyping] = useState(false);

  const [temperature, setTemperature] = useState(() =>
    parseFloat(localStorage.getItem('spectra_temp') || '0.7'));
  const [topP, setTopP] = useState(() =>
    parseFloat(localStorage.getItem('spectra_top_p') || '0.9'));
  const [ragEnabled, setRagEnabled] = useState(() =>
    localStorage.getItem('spectra_rag') !== 'false');
  const [topCandidates, setTopCandidates] = useState(() =>
    parseInt(localStorage.getItem('spectra_top_candidates') || '20', 10));
  const [convEnabled, setConvEnabled] = useState(() =>
    localStorage.getItem('spectra_conv') !== 'false');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [advisorOpen, setAdvisorOpen] = useState(false);
  const [atBottom, setAtBottom] = useState(true);

  const [activeModel, setActiveModel] = useState<string>('');
  const [availableModels, setAvailableModels] = useState<Array<{ name: string; provenance?: string }>>([]);

  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const scrollContainerRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  const MAX_HISTORY = 50;
  useEffect(() => {
    const trimmed = messages.slice(-MAX_HISTORY);
    try {
      localStorage.setItem('spectra_chat_history', JSON.stringify(trimmed));
    } catch {
      try {
        localStorage.setItem('spectra_chat_history', JSON.stringify(trimmed.slice(-Math.floor(MAX_HISTORY / 2))));
      } catch { /* ignore */ }
    }
  }, [messages]);

  useEffect(() => {
    localStorage.setItem('spectra_temp', temperature.toString());
    localStorage.setItem('spectra_top_p', topP.toString());
    localStorage.setItem('spectra_rag', ragEnabled.toString());
    localStorage.setItem('spectra_top_candidates', topCandidates.toString());
    localStorage.setItem('spectra_conv', convEnabled.toString());
  }, [temperature, topP, ragEnabled, topCandidates, convEnabled]);

  useEffect(() => {
    // N'auto-scroll que si l'utilisateur est déjà proche du bas — évite de
    // combattre un défilement manuel pendant le streaming des tokens.
    const c = scrollContainerRef.current;
    if (c && c.scrollHeight - c.scrollTop - c.clientHeight > 120) return;
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    Promise.all([configApi.getModelConfig(), fineTuningApi.getModels()])
      .then(([configRes, modelsRes]) => {
        setActiveModel(configRes.data.model ?? '');
        const chatModels = (modelsRes.data ?? []).filter((m: any) => m.type === 'chat');
        setAvailableModels(chatModels);
      })
      .catch(() => {/* silencieux si l'API est indisponible */});
  }, []);

  const handleModelSwitch = async (modelName: string) => {
    try {
      await configApi.setModelConfig({ model: modelName });
      setActiveModel(modelName);
      toast.info('Registry updated', {
        description: `Model "${modelName}" active on next llama-cpp-chat restart.`,
      });
    } catch {
      toast.error('Failed to switch model');
    }
  };

  const clearChat = () => {
    setMessages([{ role: 'assistant', content: 'Discussion cleared. System ready.', status: 'SENT' }]);
    toast.info('Chat history cleared');
  };

  /** Builds the conversation history from SENT messages to send to the backend. */
  const buildHistory = (): { role: string; content: string }[] => {
    if (!convEnabled) return [];
    return messages
      .filter(m => m.status === 'SENT' && m.content.trim())
      .slice(-20)
      .map(m => ({ role: m.role, content: m.content }));
  };

  /**
   * Envoie une requête en streaming.
   * @param text       la question
   * @param regenerate si vrai, ré-utilise le dernier message user (retire l'ancienne
   *                   réponse) au lieu d'ajouter un nouveau message user.
   */
  const submitQuery = async (text: string, regenerate = false) => {
    if (!text.trim() || isTyping) return;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const guardTimer = setTimeout(() => controller.abort(new Error('timeout')), 120_000);

    const currentInput = text;
    const history = buildHistory();

    setIsTyping(true);
    setMessages(prev => {
      let base = prev;
      if (regenerate) {
        // Retire uniquement la réponse assistant du DERNIER tour (après le dernier
        // message user) et réactive ce message user — sans toucher aux tours précédents.
        const lastUserIdx = base.findLastIndex(m => m.role === 'user');
        base = base.filter((m, i) => !(m.role === 'assistant' && i > lastUserIdx));
        const u = base.findLastIndex(m => m.role === 'user');
        if (u >= 0) base = base.map((m, i) => i === u ? { ...m, status: 'PENDING' } : m);
      } else {
        base = [...base, { role: 'user', content: currentInput, status: 'PENDING' }];
      }
      return [...base, { role: 'assistant', content: '', status: 'STREAMING' }];
    });

    // Hoisté hors du try : le catch (stop manuel) doit pouvoir figer la réponse
    // partielle avec ses sources et métriques déjà reçues.
    let sources: Source[] = [];
    const startTs = Date.now();
    let firstTokenTs: number | null = null;
    let tokenCount = 0;

    try {
      for await (const event of queryApi.queryStream(
        currentInput, ragEnabled, controller.signal, topCandidates, history, temperature, topP
      )) {
        if (event.type === 'sources') {
          try { sources = JSON.parse(event.data); } catch { /* ignore */ }
        } else if (event.type === 'token') {
          if (firstTokenTs === null) firstTokenTs = Date.now();
          tokenCount++;
          setMessages(prev => {
            const lastIdx = prev.findLastIndex(m => m.role === 'assistant');
            if (lastIdx < 0) return prev;
            return prev.map((m, i) =>
              i === lastIdx ? { ...m, content: m.content + event.data } : m
            );
          });
        } else if (event.type === 'done') {
          const metrics: MessageMetrics = {
            ttftMs: firstTokenTs ? firstTokenTs - startTs : 0,
            totalMs: Date.now() - startTs,
            tokens: tokenCount,
          };
          let meta: RagMeta | undefined;
          try {
            const parsed = JSON.parse(event.data) as StreamDoneMeta;
            meta = {
              conversationalApplied: parsed.conversationalApplied ?? false,
              correctiveApplied:     parsed.correctiveApplied     ?? false,
              selfRagApplied:        parsed.selfRagApplied         ?? false,
              ragStrategy:           parsed.ragStrategy            ?? 'STANDARD',
              rerankApplied:         parsed.rerankApplied          ?? false,
              hybridSearchApplied:   parsed.hybridSearchApplied    ?? false,
              multiQueryApplied:     parsed.multiQueryApplied      ?? false,
              compressionApplied:    parsed.compressionApplied     ?? false,
              semanticDedupApplied:  parsed.semanticDedupApplied   ?? false,
              longContextApplied:    parsed.longContextApplied     ?? false,
            };
          } catch { /* ignore */ }

          setMessages(prev => {
            const lastUserIdx = prev.findLastIndex(m => m.role === 'user' && m.content === currentInput);
            const lastAsstIdx = prev.findLastIndex(m => m.role === 'assistant');
            return prev.map((m, i) => {
              if (i === lastUserIdx) return { ...m, status: 'SENT' };
              if (i === lastAsstIdx) return { ...m, status: 'SENT', sources, ragMeta: meta, metrics };
              return m;
            });
          });
        } else if (event.type === 'error') {
          let msg = 'Spectra core is currently unreachable or timed out.';
          try { msg = JSON.parse(event.data).message ?? msg; } catch { /* ignore */ }
          toast.error('Query Uplink Failed', { description: msg });
          setMessages(prev => {
            const lastAsstIdx = prev.findLastIndex(m => m.role === 'assistant');
            return prev.map((m, i) => i === lastAsstIdx ? { ...m, status: 'ERROR' } : m);
          });
        }
      }
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        // Stop manuel : on fige la réponse partielle (SENT) au lieu de la laisser
        // en STREAMING indéfiniment. Si rien n'a été reçu, on retire la bulle vide.
        const metrics: MessageMetrics = {
          ttftMs: firstTokenTs ? firstTokenTs - startTs : 0,
          totalMs: Date.now() - startTs,
          tokens: tokenCount,
        };
        setMessages(prev => {
          const lastUserIdx = prev.findLastIndex(m => m.role === 'user' && m.content === currentInput);
          const lastAsstIdx = prev.findLastIndex(m => m.role === 'assistant');
          const removeEmpty = lastAsstIdx >= 0 && prev[lastAsstIdx].content === '';
          return prev
            .filter((_, i) => !(removeEmpty && i === lastAsstIdx))
            .map((m, i) => {
              if (i === lastUserIdx) return { ...m, status: 'SENT' as const };
              if (i === lastAsstIdx && !removeEmpty)
                return { ...m, status: 'SENT' as const, sources, metrics, stopped: true };
              return m;
            });
        });
        toast.info('Generation stopped');
        return;
      }
      toast.error('Query Uplink Failed', {
        description: 'Spectra core is currently unreachable or timed out.'
      });
      setMessages(prev => {
        const lastUserIdx = prev.findLastIndex(m => m.role === 'user' && m.content === currentInput);
        const lastAsstIdx = prev.findLastIndex(m => m.role === 'assistant');
        const removeEmpty = lastAsstIdx >= 0 && prev[lastAsstIdx].content === '';
        // lastUserIdx < lastAsstIdx always, so filtering assistant doesn't shift user's position
        return prev
          .filter((_, i) => !(removeEmpty && i === lastAsstIdx))
          .map((m, i) => i === lastUserIdx ? { ...m, status: 'ERROR' } : m);
      });
    } finally {
      clearTimeout(guardTimer);
      setIsTyping(false);
    }
  };

  const handleSend = () => {
    const text = input;
    setInput('');
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
    submitQuery(text);
  };

  /** Régénère la dernière réponse (ou relance un tour en échec). */
  const regenerateLast = () => {
    const lastUser = [...messages].reverse().find(m => m.role === 'user');
    if (lastUser) submitQuery(lastUser.content, true);
  };

  /** Charge un message user dans la saisie et tronque la conversation pour le réécrire. */
  const editMessage = (index: number) => {
    const msg = messages[index];
    if (!msg || msg.role !== 'user') return;
    setInput(msg.content);
    setMessages(prev => prev.slice(0, index));
    requestAnimationFrame(() => textareaRef.current?.focus());
  };

  const stopGeneration = () => abortRef.current?.abort();

  const copyAnswer = (text: string) => {
    navigator.clipboard.writeText(text).then(() => toast.success('Answer copied')).catch(() => {});
  };

  /** Note une réponse (👍/👎) — toggle + envoi au backend (signal de préférence DPO). */
  const sendFeedback = (index: number, rating: 'UP' | 'DOWN') => {
    const msg = messages[index];
    if (!msg || msg.role !== 'assistant') return;
    const question = messages.slice(0, index).reverse().find(m => m.role === 'user')?.content ?? '';
    const next = msg.feedback === rating ? undefined : rating;
    setMessages(prev => prev.map((m, i) => i === index ? { ...m, feedback: next } : m));
    if (next) {
      queryApi.feedback(question, msg.content, next).catch(() => { /* non bloquant */ });
      toast.success(next === 'UP' ? 'Thanks — 👍 recorded' : 'Feedback noted — 👎');
    }
  };

  const handleScroll = () => {
    const c = scrollContainerRef.current;
    if (c) setAtBottom(c.scrollHeight - c.scrollTop - c.clientHeight < 80);
  };
  const scrollToBottom = () => bottomRef.current?.scrollIntoView({ behavior: 'smooth' });

  const lastAssistantIdx = messages.findLastIndex(m => m.role === 'assistant');

  return (
    <div className="h-[calc(100vh-12rem)] flex gap-8 animate-in fade-in slide-in-from-bottom-4 duration-700">
      <aside className="w-80 bg-surface-container p-6 space-y-8 overflow-y-auto custom-scrollbar">
        <div>
          <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">Model Parameters</h3>
          <div className="space-y-6">
            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <Tooltip content="Controls randomness: Lower is more deterministic, higher is more creative.">
                  <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant cursor-help">Temperature</label>
                </Tooltip>
                <span className="text-[10px] font-mono text-primary">{temperature.toFixed(1)}</span>
              </div>
              <input
                type="range"
                className="w-full accent-primary bg-outline-variant h-1 appearance-none cursor-pointer"
                min="0" max="2" step="0.1"
                value={temperature}
                onChange={(e) => setTemperature(parseFloat(e.target.value))}
              />
            </div>

            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <Tooltip content="Limits the cumulative probability of the most likely tokens.">
                  <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant cursor-help">Top P</label>
                </Tooltip>
                <span className="text-[10px] font-mono text-primary">{topP.toFixed(2)}</span>
              </div>
              <input
                type="range"
                className="w-full accent-primary bg-outline-variant h-1 appearance-none cursor-pointer"
                min="0" max="1" step="0.05"
                value={topP}
                onChange={(e) => setTopP(parseFloat(e.target.value))}
              />
            </div>
          </div>
        </div>

        {availableModels.length > 0 && (
          <div>
            <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">Active Model</h3>
            <div className="space-y-3">
              {availableModels.map(m => (
                <button
                  key={m.name}
                  onClick={() => activeModel !== m.name && handleModelSwitch(m.name)}
                  className={`w-full text-left px-3 py-2.5 border transition-colors ${
                    activeModel === m.name
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-outline-variant/30 hover:border-primary/40 hover:bg-surface-container-high'
                  }`}
                >
                  <p className="font-mono text-[10px] font-bold uppercase truncate">{m.name}</p>
                  {m.provenance && (
                    <p className="text-[8px] uppercase tracking-widest text-on-surface-variant mt-0.5">{m.provenance}</p>
                  )}
                </button>
              ))}
              {availableModels.length > 1 && (
                <p className="text-[9px] text-outline uppercase tracking-widest leading-relaxed">
                  Effectif au prochain redémarrage du service de chat.
                </p>
              )}
            </div>
          </div>
        )}

        <div>
          <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">RAG Configuration</h3>
          <div className="space-y-3">
            <label className="flex items-center gap-3 cursor-pointer group">
              <input
                type="checkbox"
                checked={ragEnabled}
                onChange={(e) => {
                  const next = e.target.checked;
                  setRagEnabled(next);
                  toast.info(next ? 'Knowledge Base Linked' : 'Knowledge Base Disconnected');
                }}
                className="sr-only peer"
              />
              <div className="w-4 h-4 border border-primary flex items-center justify-center group-hover:bg-primary/10 transition-colors peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-primary peer-focus-visible:outline-offset-2">
                {ragEnabled && <div className="w-2 h-2 bg-primary"></div>}
              </div>
              <span className="text-xs font-label uppercase tracking-widest">Enable Knowledge Base</span>
            </label>

            {ragEnabled && (
              <>
                <label className="flex items-center gap-3 cursor-pointer group">
                  <input
                    type="checkbox"
                    checked={convEnabled}
                    onChange={(e) => {
                      const next = e.target.checked;
                      setConvEnabled(next);
                      toast.info(next ? 'Conversational RAG enabled' : 'Conversational RAG disabled');
                    }}
                    className="sr-only peer"
                  />
                  <div className="w-4 h-4 border border-secondary flex items-center justify-center group-hover:bg-secondary/10 transition-colors peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-secondary peer-focus-visible:outline-offset-2">
                    {convEnabled && <div className="w-2 h-2 bg-secondary"></div>}
                  </div>
                  <Tooltip content="Sends the conversation history to rephrase the question before retrieval (Conversational RAG).">
                    <span className="text-xs font-label uppercase tracking-widest cursor-help">Conversational History</span>
                  </Tooltip>
                </label>

                <div>
                  <button
                    className="flex items-center gap-1 text-[9px] uppercase tracking-widest text-on-surface-variant hover:text-primary transition-colors mt-1"
                    onClick={() => setShowAdvanced(v => !v)}
                  >
                    <span className="material-symbols-outlined text-[11px]">{showAdvanced ? 'expand_less' : 'expand_more'}</span>
                    Advanced
                  </button>

                  {showAdvanced && (
                    <div className="mt-3 space-y-2">
                      <div className="flex justify-between items-center">
                        <Tooltip content="Number of candidates sent to the re-ranker (higher = better coverage, slower).">
                          <label className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant cursor-help">
                            Top Candidates
                          </label>
                        </Tooltip>
                        <span className="text-[10px] font-mono text-primary">{topCandidates}</span>
                      </div>
                      <input
                        type="range"
                        className="w-full accent-primary bg-outline-variant h-1 appearance-none cursor-pointer"
                        min="5" max="50" step="5"
                        value={topCandidates}
                        onChange={(e) => setTopCandidates(parseInt(e.target.value, 10))}
                      />
                    </div>
                  )}
                </div>
              </>
            )}
          </div>
        </div>

        <div className="pt-8 border-t border-outline-variant/10 space-y-3">
          <button
            onClick={() => setAdvisorOpen(true)}
            className="w-full py-3 px-4 border border-primary/30 text-primary text-[10px] font-headline uppercase tracking-widest hover:bg-primary/5 transition-colors flex items-center justify-center gap-2"
          >
            <span className="material-symbols-outlined text-sm">psychology</span>
            Conseiller RAG
          </button>
          <button
            onClick={clearChat}
            className="w-full py-3 px-4 border border-error/30 text-error text-[10px] font-headline uppercase tracking-widest hover:bg-error/5 transition-colors flex items-center justify-center gap-2"
          >
            <span className="material-symbols-outlined text-sm">delete_sweep</span>
            Clear Chat History
          </button>
        </div>
      </aside>

      <div className="flex-1 flex flex-col bg-surface-container overflow-hidden relative">
        <div
          ref={scrollContainerRef}
          onScroll={handleScroll}
          role="log"
          aria-live="polite"
          aria-busy={isTyping}
          aria-label="Conversation"
          className="flex-1 overflow-y-auto p-8 space-y-8 custom-scrollbar"
        >
          {messages.map((msg, i) => (
            <div key={i} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
              <div className={`max-w-[80%] p-6 group relative ${
                msg.role === 'user'
                  ? 'bg-surface-container-high border-r-2 border-secondary'
                  : 'bg-surface-container-lowest border-l-2 border-primary'
              } ${msg.status === 'PENDING' ? 'opacity-70' : ''}`}>

                {msg.role === 'user' && (
                  <div className="absolute -left-8 top-1/2 -translate-y-1/2">
                    {msg.status === 'PENDING' && <span className="material-symbols-outlined text-outline text-sm animate-spin">sync</span>}
                    {msg.status === 'ERROR' && <span className="material-symbols-outlined text-error text-sm">error</span>}
                    {msg.status === 'SENT' && <span className="material-symbols-outlined text-primary text-sm">check_circle</span>}
                  </div>
                )}

                <p className="font-label text-[10px] uppercase tracking-[0.1em] text-on-surface-variant mb-3">
                  {msg.role === 'user' ? 'Architect' : 'Spectra Core'}
                </p>
                {msg.role === 'assistant' ? (
                  <div className="text-sm">
                    {msg.content && <ChatMarkdown content={msg.content} />}
                    {msg.status === 'STREAMING' && (
                      <span className="inline-block w-1.5 h-3.5 bg-primary ml-0.5 animate-pulse align-middle" />
                    )}
                  </div>
                ) : (
                  <p className="text-sm font-body leading-relaxed whitespace-pre-wrap">{msg.content}</p>
                )}

                {msg.sources && msg.sources.length > 0 && (
                  <div className="mt-4 pt-4 border-t border-outline-variant/20">
                    <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-1">Sources ({msg.sources.length})</p>
                    {msg.sources.map((src, j) => <SourceItem key={j} src={src} />)}
                  </div>
                )}

                {msg.ragMeta && msg.status === 'SENT' && msg.role === 'assistant' && (
                  <RagBadges meta={msg.ragMeta} />
                )}

                {/* Pied de bulle : métriques + feedback (toujours) + copy/regenerate (survol) */}
                {msg.role === 'assistant' && msg.status === 'SENT' && msg.content && (
                  <div className="mt-3 flex items-center justify-between gap-3">
                    {msg.metrics ? (
                      <div className="flex items-center gap-3 text-[8px] font-mono text-outline">
                        <span title="Time to first token">TTFT {(msg.metrics.ttftMs / 1000).toFixed(1)}s</span>
                        <span title="Total time">{(msg.metrics.totalMs / 1000).toFixed(1)}s</span>
                        <span title="Tokens (approx.)">{msg.metrics.tokens} tok</span>
                        {msg.stopped && (
                          <span className="text-error font-bold uppercase tracking-wider" title="Generation stopped — answer may be incomplete">
                            stopped
                          </span>
                        )}
                      </div>
                    ) : <span />}

                    <div className="flex items-center gap-1">
                      <button type="button" onClick={() => sendFeedback(i, 'UP')}
                        aria-label="Good answer" aria-pressed={msg.feedback === 'UP'}
                        className={`material-symbols-outlined text-[14px] px-1 transition-colors ${msg.feedback === 'UP' ? 'text-primary' : 'text-outline hover:text-primary'}`}>thumb_up</button>
                      <button type="button" onClick={() => sendFeedback(i, 'DOWN')}
                        aria-label="Bad answer" aria-pressed={msg.feedback === 'DOWN'}
                        className={`material-symbols-outlined text-[14px] px-1 transition-colors ${msg.feedback === 'DOWN' ? 'text-error' : 'text-outline hover:text-error'}`}>thumb_down</button>

                      <span className="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                        <button type="button" onClick={() => copyAnswer(msg.content)} aria-label="Copy answer"
                          className="flex items-center gap-1 text-[9px] uppercase tracking-widest text-outline hover:text-primary transition-colors px-1.5 py-0.5">
                          <span aria-hidden="true" className="material-symbols-outlined text-[13px]">content_copy</span>Copy
                        </button>
                        {i === lastAssistantIdx && (
                          <button type="button" onClick={regenerateLast} disabled={isTyping} aria-label="Regenerate"
                            className="flex items-center gap-1 text-[9px] uppercase tracking-widest text-outline hover:text-primary transition-colors px-1.5 py-0.5 disabled:opacity-40">
                            <span aria-hidden="true" className="material-symbols-outlined text-[13px]">refresh</span>Regenerate
                          </button>
                        )}
                      </span>
                    </div>
                  </div>
                )}
                {msg.role === 'user' && (
                  <div className="mt-2 flex items-center justify-end gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                    <button type="button" onClick={() => editMessage(i)} disabled={isTyping} aria-label="Edit message"
                      className="flex items-center gap-1 text-[9px] uppercase tracking-widest text-outline hover:text-secondary transition-colors px-1.5 py-0.5 disabled:opacity-40">
                      <span aria-hidden="true" className="material-symbols-outlined text-[13px]">edit</span>Edit
                    </button>
                    {msg.status === 'ERROR' && (
                      <button type="button" onClick={regenerateLast} disabled={isTyping} aria-label="Retry"
                        className="flex items-center gap-1 text-[9px] uppercase tracking-widest text-error hover:text-error/80 transition-colors px-1.5 py-0.5 disabled:opacity-40">
                        <span aria-hidden="true" className="material-symbols-outlined text-[13px]">replay</span>Retry
                      </button>
                    )}
                  </div>
                )}
              </div>
            </div>
          ))}
          {isTyping && !messages.some(m => m.status === 'STREAMING') && (
            <div className="flex justify-start">
              <div className="bg-surface-container-lowest border-l-2 border-primary p-4 flex gap-2 items-center">
                <div className="flex gap-1">
                  <div className="w-1 h-1 bg-primary animate-bounce"></div>
                  <div className="w-1 h-1 bg-primary animate-bounce [animation-delay:0.2s]"></div>
                  <div className="w-1 h-1 bg-primary animate-bounce [animation-delay:0.4s]"></div>
                </div>
                <span className="text-[9px] uppercase tracking-widest text-outline">Processing...</span>
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>

        {/* Bouton « descendre en bas » quand on a scrollé vers le haut */}
        {!atBottom && (
          <button
            type="button"
            onClick={scrollToBottom}
            aria-label="Scroll to latest message"
            className="absolute bottom-28 right-6 z-10 flex items-center gap-1 bg-surface-container-high border border-outline-variant/30 text-on-surface-variant hover:text-primary px-2.5 py-1.5 shadow-lg text-[9px] uppercase tracking-widest transition-colors animate-in fade-in slide-in-from-bottom-2"
          >
            <span aria-hidden="true" className={`material-symbols-outlined text-[14px] ${isTyping ? 'text-primary animate-bounce' : ''}`}>arrow_downward</span>
            {isTyping ? 'New' : 'Latest'}
          </button>
        )}

        <div className="p-8 border-t border-outline-variant/10">
          {convEnabled && messages.filter(m => m.status === 'SENT').length > 1 && (
            <p className="text-[8px] font-label uppercase tracking-widest text-secondary mb-2 flex items-center gap-1">
              <span className="material-symbols-outlined text-[10px]">forum</span>
              Conversational — {messages.filter(m => m.status === 'SENT').length} messages in history
            </p>
          )}
          <div className="flex items-end gap-3 bg-surface-container-lowest border border-outline-variant/20 p-2">
            <label htmlFor="chat-input" className="sr-only">Message</label>
            <textarea
              id="chat-input"
              ref={textareaRef}
              rows={1}
              className="flex-1 bg-transparent border-none focus:ring-0 focus:outline-none text-sm font-body px-4 py-2 resize-none max-h-40 custom-scrollbar"
              placeholder="Ask a question…  (Enter to send · Shift+Enter for a new line)"
              value={input}
              onChange={(e) => {
                setInput(e.target.value);
                const el = e.target;
                el.style.height = 'auto';
                el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  if (!isTyping) handleSend();
                }
              }}
            />
            {isTyping ? (
              <button
                type="button"
                onClick={stopGeneration}
                aria-label="Stop generation"
                className="bg-error/90 text-on-error p-2 transition-all hover:bg-error flex items-center justify-center"
              >
                <span aria-hidden="true" className="material-symbols-outlined">stop</span>
              </button>
            ) : (
              <button
                type="button"
                onClick={handleSend}
                disabled={!input.trim()}
                aria-label="Send message"
                className="bg-primary text-on-primary-fixed p-2 transition-all hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed flex items-center justify-center"
              >
                <span aria-hidden="true" className="material-symbols-outlined">send</span>
              </button>
            )}
          </div>
        </div>
      </div>
      <RagAdvisor open={advisorOpen} onClose={() => setAdvisorOpen(false)} />
    </div>
  );
};

export default Playground;
