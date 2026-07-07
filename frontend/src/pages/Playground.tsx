import { useState, useEffect, useRef } from 'react';
import type { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import { queryApi, configApi, fineTuningApi, ingestApi, healthApi } from '../services/api';
import type { StreamDoneMeta } from '../services/api';
import type { ServiceStatus } from '../types/api';
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
  agenticIterations?: number;
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

const RagBadges: FC<{ meta: RagMeta; onShowTrace?: () => void }> = ({ meta, onShowTrace }) => {
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
    <div className="mt-3 pt-3 border-t border-outline-variant/20 flex flex-wrap items-center justify-between gap-2">
      <div className="flex flex-wrap items-center gap-1.5">
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
      {onShowTrace && (
        <button
          type="button"
          onClick={onShowTrace}
          className="flex items-center gap-1 text-[9px] uppercase tracking-widest text-outline hover:text-primary transition-colors px-1.5 py-0.5"
          aria-label="View algorithm trace details"
        >
          <span className="material-symbols-outlined text-[13px]">insights</span>
          Trace
        </button>
      )}
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
  const [traceMsg, setTraceMsg] = useState<Message | null>(null);
  const [messages, setMessages] = useState<Message[]>(() => {
    const saved = localStorage.getItem('spectra_chat_history');
    if (!saved) return [defaultWelcome];
    try {
      const parsed = JSON.parse(saved) as Message[];
      // Assainit les statuts transitoires persistés lors d'un reload en plein
      // streaming : sans ça, une réponse restaurée en STREAMING garderait un curseur
      // clignotant à vie et un message user en PENDING. Les sources/ragMeta/métriques
      // sont préservés tels quels par le round-trip JSON.
      return parsed
        .filter(m => !(m.role === 'assistant' && m.status === 'STREAMING' && !m.content?.trim()))
        .map(m => (m.status === 'STREAMING' || m.status === 'PENDING') ? { ...m, status: 'SENT' as const } : m);
    } catch { return [defaultWelcome]; }
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
  const [regenMenuOpen, setRegenMenuOpen] = useState(false);
  const [exportMenuOpen, setExportMenuOpen] = useState(false);

  const [activeModel, setActiveModel] = useState<string>('');

  // Santé des services (polling 20 s) — pour signaler une panne et bloquer l'envoi
  // quand le LLM de chat est down, plutôt que de laisser la requête échouer en timeout.
  const { data: services } = useQuery<ServiceStatus[]>({
    queryKey: ['playground-health'],
    queryFn: async () => (await healthApi.getServices()).data,
    refetchInterval: 20_000,
    retry: 1,
    staleTime: 10_000,
  });
  const chatService = services?.find(s => s.name === 'llama-cpp') ?? services?.[0];
  const chromaService = services?.find(s => s.name === 'chromadb');

  // Modèles de chat disponibles + modèle actif — via React Query pour se rafraîchir au retour
  // d'onglet (un modèle installé dans le Model Hub apparaît sans recharger la page).
  const { data: modelsData } = useQuery<{ activeModel: string; chatModels: Array<{ name: string; provenance?: string }> }>({
    queryKey: ['playground-models'],
    queryFn: async () => {
      const [configRes, modelsRes] = await Promise.all([configApi.getModelConfig(), fineTuningApi.getModels()]);
      const chatModels = (modelsRes.data ?? []).filter((m: any) => m.type === 'chat');
      return { activeModel: configRes.data.model ?? '', chatModels };
    },
    refetchOnWindowFocus: true,
    staleTime: 30_000,
  });
  const availableModels = modelsData?.chatModels ?? [];
  // `undefined` avant le premier poll → on n'empêche pas l'envoi (optimiste).
  const llmDown = chatService ? !chatService.available : false;
  const ragDegraded = ragEnabled && chromaService ? !chromaService.available : false;

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

  // Initialise le modèle actif depuis la config, sans écraser un choix optimiste de l'utilisateur.
  useEffect(() => {
    if (modelsData?.activeModel && !activeModel) setActiveModel(modelsData.activeModel);
  }, [modelsData?.activeModel, activeModel]);

  const handleModelSwitch = async (modelName: string) => {
    try {
      await configApi.setModelConfig({ model: modelName });
      setActiveModel(modelName);
      toast.info('Active model updated', {
        description: `llm-chat reloads "${modelName}" automatically within a few seconds.`,
      });
    } catch (error: any) {
      // 400 : alias inconnu du registre — le détail liste les modèles enregistrés.
      toast.error('Failed to switch model', {
        description: error?.response?.data?.error ?? error?.response?.data?.detail ?? error?.message,
      });
    }
  };

  const clearChat = () => {
    setMessages([{ role: 'assistant', content: 'Discussion cleared. System ready.', status: 'SENT' }]);
    toast.info('Chat history cleared');
  };

  /** Déclenche le téléchargement d'un blob texte côté navigateur. */
  const downloadFile = (filename: string, mime: string, content: string) => {
    const url = URL.createObjectURL(new Blob([content], { type: mime }));
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  };

  /** Sérialise la conversation en Markdown lisible (question, réponse, sources, pipeline, métriques). */
  const conversationToMarkdown = (): string => {
    const lines: string[] = [
      '# Spectra Playground — Conversation',
      `_Exported ${new Date().toISOString()}${activeModel ? ` · model: ${activeModel}` : ''}_`,
      '',
    ];
    for (const m of messages) {
      if (!m.content?.trim()) continue;
      lines.push(m.role === 'user' ? '## 🧑 Architect' : '## 🤖 Spectra Core', '', m.content.trim(), '');
      if (m.role === 'assistant') {
        if (m.sources?.length) {
          lines.push('**Sources:**');
          for (const s of m.sources) {
            const pct = typeof s.distance === 'number' ? ` (${Math.max(0, Math.min(100, Math.round((1 - s.distance) * 100)))}%)` : '';
            lines.push(`- ${s.sourceFile}${pct}`);
          }
          lines.push('');
        }
        if (m.ragMeta) {
          const flags = Object.entries(m.ragMeta)
            .filter(([k, v]) => v === true && k.endsWith('Applied'))
            .map(([k]) => k.replace('Applied', ''));
          lines.push(`**Pipeline:** ${m.ragMeta.ragStrategy}${flags.length ? ` · ${flags.join(', ')}` : ''}`, '');
        }
        if (m.metrics) {
          lines.push(`_TTFT ${(m.metrics.ttftMs / 1000).toFixed(1)}s · ${(m.metrics.totalMs / 1000).toFixed(1)}s · ${m.metrics.tokens} tok${m.stopped ? ' · stopped' : ''}_`, '');
        }
      }
      lines.push('---', '');
    }
    return lines.join('\n');
  };

  /** Exporte la conversation courante (Markdown ou JSON). */
  const exportConversation = (format: 'md' | 'json') => {
    setExportMenuOpen(false);
    const real = messages.filter(m => m.content?.trim());
    if (real.length <= 1) { toast.info('Nothing to export yet'); return; }
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
    if (format === 'md') {
      downloadFile(`spectra-chat-${stamp}.md`, 'text/markdown', conversationToMarkdown());
    } else {
      downloadFile(`spectra-chat-${stamp}.json`, 'application/json', JSON.stringify(messages, null, 2));
    }
    toast.success(`Conversation exported (${format.toUpperCase()})`);
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
   * @param text         la question
   * @param regenerate   si vrai, ré-utilise le dernier message user (retire l'ancienne
   *                     réponse) au lieu d'ajouter un nouveau message user.
   * @param tempOverride température à utiliser pour CET appel uniquement (régénération
   *                     « plus factuel / plus créatif ») sans modifier le slider global.
   */
  const submitQuery = async (text: string, regenerate = false, tempOverride?: number) => {
    if (!text.trim() || isTyping) return;
    if (llmDown) {
      toast.error('Model offline', { description: 'The chat model service is unreachable. Check llama-cpp-chat.' });
      return;
    }
    const effTemperature = tempOverride ?? temperature;

    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    // Garde d'inactivité GLISSANTE : on n'annule qu'après 120 s SANS aucun token,
    // pour ne pas tuer une génération longue mais saine (CPU : 30–120 s/chunk).
    // On annule sans "reason" → AbortError, et un flag distingue timeout / stop manuel.
    let timedOut = false;
    const INACTIVITY_MS = 120_000;
    let guardTimer = setTimeout(() => { timedOut = true; controller.abort(); }, INACTIVITY_MS);
    const resetGuard = () => {
      clearTimeout(guardTimer);
      guardTimer = setTimeout(() => { timedOut = true; controller.abort(); }, INACTIVITY_MS);
    };

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
        currentInput, ragEnabled, controller.signal, topCandidates, history, effTemperature, topP
      )) {
        if (event.type === 'sources') {
          try { sources = JSON.parse(event.data); } catch { /* ignore */ }
        } else if (event.type === 'token') {
          if (firstTokenTs === null) firstTokenTs = Date.now();
          tokenCount++;
          resetGuard(); // activité : repousser la garde d'inactivité
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
            const lastUserIdx = prev.findLastIndex(m => m.role === 'user' && m.content === currentInput);
            const lastAsstIdx = prev.findLastIndex(m => m.role === 'assistant');
            const removeEmpty = lastAsstIdx >= 0 && prev[lastAsstIdx].content === '';
            return prev
              .filter((_, i) => !(removeEmpty && i === lastAsstIdx))
              .map((m, i) => {
                // Marquer le message USER en ERROR pour faire apparaître le bouton Retry.
                if (i === lastUserIdx) return { ...m, status: 'ERROR' as const };
                if (i === lastAsstIdx && !removeEmpty) return { ...m, status: 'ERROR' as const };
                return m;
              });
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
        if (timedOut) toast.warning('Generation timed out (stalled)');
        else toast.info('Generation stopped');
        return;
      }
      toast.error('Query Uplink Failed', {
        description: 'Spectra core is currently unreachable or timed out.'
      });
      setMessages(prev => {
        const lastUserIdx = prev.findLastIndex(m => m.role === 'user' && m.content === currentInput);
        const lastAsstIdx = prev.findLastIndex(m => m.role === 'assistant');
        const removeEmpty = lastAsstIdx >= 0 && prev[lastAsstIdx].content === '';
        // lastUserIdx < lastAsstIdx always, so filtering assistant doesn't shift user's position.
        // Une bulle assistant partielle NE DOIT PAS rester en STREAMING (curseur clignotant
        // éternel) : on la fige en ERROR, et on met le message user en ERROR pour le Retry.
        return prev
          .filter((_, i) => !(removeEmpty && i === lastAsstIdx))
          .map((m, i) => {
            if (i === lastUserIdx) return { ...m, status: 'ERROR' as const };
            if (i === lastAsstIdx && !removeEmpty) return { ...m, status: 'ERROR' as const };
            return m;
          });
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

  /**
   * Régénère la dernière réponse (ou relance un tour en échec).
   * @param tempOverride température pour cette régénération (cf. menu « plus factuel/créatif »).
   */
  const regenerateLast = (tempOverride?: number) => {
    setRegenMenuOpen(false);
    const lastUser = [...messages].reverse().find(m => m.role === 'user');
    if (lastUser) submitQuery(lastUser.content, true, tempOverride);
  };

  const clamp = (t: number) => Math.max(0, Math.min(2, Math.round(t * 10) / 10));
  /** Variantes de régénération : décale la température autour du réglage courant. */
  const regenVariants = [
    { label: 'Same temperature', icon: 'refresh',     temp: undefined as number | undefined },
    { label: 'More factual',     icon: 'target',      temp: clamp(temperature - 0.4) },
    { label: 'More creative',    icon: 'auto_awesome', temp: clamp(temperature + 0.4) },
  ];

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
        {services && (
          <div>
            <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">System</h3>
            <div className="space-y-2">
              {[
                { label: 'Chat Model', svc: chatService, warn: llmDown },
                ...(ragEnabled ? [{ label: 'Knowledge Base', svc: chromaService, warn: ragDegraded }] : []),
              ].map(({ label, svc, warn }) => (
                <div key={label} className="flex items-center justify-between">
                  <span className="text-[10px] font-label uppercase tracking-widest text-on-surface-variant">{label}</span>
                  <span className={`flex items-center gap-1.5 text-[9px] font-mono uppercase tracking-wider ${warn ? 'text-error' : 'text-primary'}`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${warn ? 'bg-error animate-pulse' : 'bg-primary'}`} />
                    {svc?.available ? 'online' : 'offline'}
                  </span>
                </div>
              ))}
              {llmDown && (
                <p className="text-[9px] text-error leading-relaxed pt-1">
                  Chat model unreachable — start <span className="font-mono">llama-cpp-chat</span> to send messages.
                </p>
              )}
              {ragDegraded && !llmDown && (
                <p className="text-[9px] text-error leading-relaxed pt-1">
                  Vector DB unreachable — retrieval may fail. Disable the Knowledge Base for a direct answer.
                </p>
              )}
            </div>
          </div>
        )}
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
                  Effective on the next chat service restart.
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
            RAG Advisor
          </button>
          <div className="relative">
            <button
              onClick={() => setExportMenuOpen(o => !o)}
              aria-haspopup="menu" aria-expanded={exportMenuOpen}
              className="w-full py-3 px-4 border border-outline-variant/40 text-on-surface-variant text-[10px] font-headline uppercase tracking-widest hover:border-primary/40 hover:text-primary transition-colors flex items-center justify-center gap-2"
            >
              <span className="material-symbols-outlined text-sm">download</span>
              Export Conversation
            </button>
            {exportMenuOpen && (
              <>
                <button type="button" aria-hidden="true" tabIndex={-1}
                  className="fixed inset-0 z-10 cursor-default" onClick={() => setExportMenuOpen(false)} />
                <div role="menu"
                  className="absolute left-0 right-0 bottom-full mb-1 z-20 bg-surface-container-high border border-outline-variant/30 shadow-lg py-1 animate-in fade-in slide-in-from-bottom-1">
                  <button type="button" role="menuitem" onClick={() => exportConversation('md')}
                    className="w-full flex items-center gap-2 px-3 py-2 text-left text-[10px] uppercase tracking-widest text-on-surface-variant hover:bg-primary/10 hover:text-primary transition-colors">
                    <span aria-hidden="true" className="material-symbols-outlined text-[14px]">description</span>As Markdown
                  </button>
                  <button type="button" role="menuitem" onClick={() => exportConversation('json')}
                    className="w-full flex items-center gap-2 px-3 py-2 text-left text-[10px] uppercase tracking-widest text-on-surface-variant hover:bg-primary/10 hover:text-primary transition-colors">
                    <span aria-hidden="true" className="material-symbols-outlined text-[14px]">data_object</span>As JSON
                  </button>
                </div>
              </>
            )}
          </div>
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
                  <RagBadges meta={msg.ragMeta} onShowTrace={() => setTraceMsg(msg)} />
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
                          <div className="relative">
                            <button type="button" onClick={() => setRegenMenuOpen(o => !o)} disabled={isTyping}
                              aria-label="Regenerate" aria-haspopup="menu" aria-expanded={regenMenuOpen}
                              className="flex items-center gap-1 text-[9px] uppercase tracking-widest text-outline hover:text-primary transition-colors px-1.5 py-0.5 disabled:opacity-40">
                              <span aria-hidden="true" className="material-symbols-outlined text-[13px]">refresh</span>Regenerate
                              <span aria-hidden="true" className={`material-symbols-outlined text-[12px] transition-transform ${regenMenuOpen ? 'rotate-180' : ''}`}>expand_more</span>
                            </button>
                            {regenMenuOpen && (
                              <>
                                {/* Couche de fermeture au clic extérieur. */}
                                <button type="button" aria-hidden="true" tabIndex={-1}
                                  className="fixed inset-0 z-10 cursor-default" onClick={() => setRegenMenuOpen(false)} />
                                <div role="menu"
                                  className="absolute right-0 bottom-full mb-1 z-20 w-44 bg-surface-container-high border border-outline-variant/30 shadow-lg py-1 animate-in fade-in slide-in-from-bottom-1">
                                  {regenVariants.map(v => (
                                    <button key={v.label} type="button" role="menuitem"
                                      onClick={() => regenerateLast(v.temp)}
                                      className="w-full flex items-center gap-2 px-3 py-1.5 text-left text-[10px] uppercase tracking-widest text-on-surface-variant hover:bg-primary/10 hover:text-primary transition-colors">
                                      <span aria-hidden="true" className="material-symbols-outlined text-[13px]">{v.icon}</span>
                                      <span className="flex-1">{v.label}</span>
                                      {v.temp !== undefined && <span className="font-mono text-[8px] text-outline">{v.temp.toFixed(1)}</span>}
                                    </button>
                                  ))}
                                </div>
                              </>
                            )}
                          </div>
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
                      <button type="button" onClick={() => regenerateLast()} disabled={isTyping} aria-label="Retry"
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
              placeholder={llmDown ? 'Chat model offline — start llama-cpp-chat to send messages' : 'Ask a question…  (Enter to send · Shift+Enter for a new line)'}
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
                disabled={!input.trim() || llmDown}
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
      {traceMsg && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-black/60 backdrop-blur-sm animate-in fade-in duration-300">
          <div className="bg-surface-container border border-outline-variant/30 shadow-2xl w-full max-w-4xl max-h-full flex flex-col rounded-xl overflow-hidden animate-in zoom-in-95 duration-300">
            <header className="px-6 py-4 border-b border-outline-variant/10 flex items-center justify-between bg-surface-container-high shrink-0">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-primary/20 text-primary flex items-center justify-center">
                  <span className="material-symbols-outlined text-sm">insights</span>
                </div>
                <div>
                  <h2 className="font-headline font-bold text-lg text-on-surface">Algorithm Trace</h2>
                  <p className="text-[10px] uppercase tracking-widest text-on-surface-variant">Execution details for the selected response</p>
                </div>
              </div>
              <button
                onClick={() => setTraceMsg(null)}
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
                    {traceMsg.ragMeta?.ragStrategy === 'AGENTIC' && traceMsg.ragMeta?.agenticIterations && (
                      <div className="text-right">
                        <p className="text-2xl font-mono text-primary">{traceMsg.ragMeta?.agenticIterations}</p>
                        <p className="text-[10px] uppercase tracking-widest text-on-surface-variant mt-1">Iterations</p>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              <div className="space-y-3">
                <h3 className="font-headline font-bold text-sm text-secondary uppercase tracking-widest flex items-center gap-2">
                  <span className="material-symbols-outlined text-base">filter_alt</span>
                  Optimizations Triggered
                </h3>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                   {[
                      { active: traceMsg.ragMeta?.hybridSearchApplied, label: 'Hybrid Search', desc: 'Merged BM25 (exact text) + ChromaDB (semantic vectors) via Reciprocal Rank Fusion.' },
                      { active: traceMsg.ragMeta?.rerankApplied, label: 'Cross-Encoder', desc: 'Candidates rescored jointly with the query by a Cross-Encoder for higher accuracy.' },
                      { active: traceMsg.ragMeta?.multiQueryApplied, label: 'Multi-Query', desc: 'LLM generated query variations to broaden the search net.' },
                      { active: traceMsg.ragMeta?.compressionApplied, label: 'Context Compression', desc: 'Extracted only the relevant sentences from large chunks.' },
                      { active: traceMsg.ragMeta?.semanticDedupApplied, label: 'Semantic Dedup', desc: 'Filtered out near-duplicate chunks via Jaccard similarity.' },
                      { active: traceMsg.ragMeta?.correctiveApplied, label: 'Corrective RAG', desc: 'LLM graded retrieved chunks and discarded irrelevant ones.' },
                   ].map(opt => (
                     <div key={opt.label} className={`p-4 rounded-lg border ${opt.active ? 'bg-secondary/10 border-secondary/30' : 'bg-surface-container-low border-outline-variant/10 opacity-50'}`}>
                        <div className="flex items-center gap-2 mb-2">
                          <span className={`material-symbols-outlined text-sm ${opt.active ? 'text-secondary' : 'text-on-surface-variant'}`}>
                            {opt.active ? 'check_circle' : 'cancel'}
                          </span>
                          <span className={`font-bold text-xs ${opt.active ? 'text-secondary' : 'text-on-surface-variant'}`}>{opt.label}</span>
                        </div>
                        <p className="text-[10px] text-on-surface-variant leading-relaxed">{opt.desc}</p>
                     </div>
                   ))}
                </div>
              </div>

              {traceMsg.sources && traceMsg.sources.length > 0 && (
                <div className="space-y-3">
                  <h3 className="font-headline font-bold text-sm text-primary uppercase tracking-widest flex items-center gap-2">
                    <span className="material-symbols-outlined text-base">format_list_numbered</span>
                    Final Context Rendered to LLM
                  </h3>
                  <div className="space-y-2">
                     {traceMsg.sources.map((src, i) => {
                       const pct = typeof src.distance === 'number' ? Math.max(0, Math.min(100, Math.round((1 - src.distance) * 100))) : null;
                       return (
                        <div key={i} className="bg-surface-container-low border border-outline-variant/10 p-3 rounded text-xs space-y-1">
                           <div className="flex items-center justify-between">
                             <span className="font-bold text-on-surface break-all">{src.sourceFile}</span>
                             {pct !== null && (
                               <span className="text-[10px] bg-primary/20 text-primary px-1.5 rounded">{pct}% relevance</span>
                             )}
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
      )}
    </div>
  );
};

export default Playground;
