import { useState, useEffect, useRef } from 'react';
import type { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Trans, useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { queryApi, configApi, fineTuningApi, ingestApi, healthApi } from '../services/api';
import type { StreamDoneMeta } from '../services/api';
import type { ServiceStatus } from '../types/api';
import Tooltip from '../components/Tooltip';
import RagAdvisor from '../components/RagAdvisor';
import ChatMarkdown from '../components/ChatMarkdown';
import { useFocusTrap } from '../hooks/useFocusTrap';

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

const BADGE_LABELS = ['CONV', 'CORR', 'SELF', 'RRNK', 'HYB', 'MQ', 'CMPR', 'DEDUP', 'FULL'] as const;

const RagBadges: FC<{ meta: RagMeta; onShowTrace?: () => void }> = ({ meta, onShowTrace }) => {
  const { t } = useTranslation();
  const activeByLabel: Record<(typeof BADGE_LABELS)[number], boolean> = {
    CONV:  meta.conversationalApplied,
    CORR:  meta.correctiveApplied,
    SELF:  meta.selfRagApplied,
    RRNK:  meta.rerankApplied,
    HYB:   meta.hybridSearchApplied,
    MQ:    meta.multiQueryApplied,
    CMPR:  meta.compressionApplied,
    DEDUP: meta.semanticDedupApplied,
    FULL:  meta.longContextApplied,
  };

  const activeBadges = BADGE_LABELS.filter(label => activeByLabel[label]);
  if (activeBadges.length === 0 && meta.ragStrategy === 'STANDARD') return null;

  return (
    <div className="mt-3 pt-3 border-t border-outline-variant/20 flex flex-wrap items-center justify-between gap-2">
      <div className="flex flex-wrap items-center gap-1.5">
        <Tooltip content={t('playground.strategyTooltip', { name: meta.ragStrategy })}>
          <span className={`text-[10px] font-bold px-1.5 py-0.5 border uppercase tracking-wider cursor-help ${STRATEGY_COLORS[meta.ragStrategy] ?? STRATEGY_COLORS.STANDARD}`}>
            {meta.ragStrategy}
          </span>
        </Tooltip>
        {activeBadges.map(label => (
          <Tooltip key={label} content={t(`playground.badges.${label}`)}>
            <span className="text-[10px] font-bold px-1.5 py-0.5 border border-primary/30 text-primary bg-primary/5 uppercase tracking-wider cursor-help">
              {label}
            </span>
          </Tooltip>
        ))}
      </div>
      {onShowTrace && (
        <button
          type="button"
          onClick={onShowTrace}
          className="flex items-center gap-1 text-[10px] uppercase tracking-widest text-outline hover:text-primary transition-colors px-1.5 py-0.5"
          aria-label={t('playground.traceAria')}
        >
          <span className="material-symbols-outlined text-[13px]">insights</span>
          {t('playground.trace')}
        </button>
      )}
    </div>
  );
};

/** Source de réponse dépliable : nom de fichier + pertinence + passage récupéré. */
const SourceItem: FC<{ src: Source; expert?: boolean }> = ({ src, expert = false }) => {
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const snippet = src.preview ?? src.text ?? '';
  const pct = typeof src.distance === 'number' ? Math.max(0, Math.min(100, Math.round((1 - src.distance) * 100))) : null;

  const openInDatabase = async () => {
    try {
      const res = await ingestApi.getHistory({ q: src.sourceFile, size: 5 });
      const items: any[] = res.data?.content ?? res.data ?? [];
      const match = items.find(d => d.fileName === src.sourceFile) ?? items[0];
      if (match?.sha256) navigate(`/documents?doc=${encodeURIComponent(match.sha256)}`);
      else toast.error(t('playground.docNotFound'));
    } catch {
      toast.error(t('playground.docOpenFailed'));
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
        <span className="font-mono text-[10px] text-on-surface-variant truncate flex-1">{src.sourceFile}</span>
        {pct !== null && <span className="text-[10px] font-bold text-primary shrink-0" title={t('playground.relevance')}>{pct}%</span>}
        <span aria-hidden="true" className={`material-symbols-outlined text-[12px] text-outline shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}>expand_more</span>
      </button>
      {open && (
        <div className="pl-5 pb-2 space-y-1.5">
          {snippet
            ? <p className="text-[11px] text-on-surface-variant leading-relaxed whitespace-pre-wrap">{snippet}</p>
            : <p className="text-[11px] text-outline italic">{t('playground.noPreview')}</p>}
          <div className={`flex items-center ${expert ? 'justify-between' : 'justify-end'}`}>
            {expert && (
              <span className="text-[10px] font-mono text-outline">distance: {typeof src.distance === 'number' ? src.distance.toFixed(3) : '—'}</span>
            )}
            <button
              type="button"
              onClick={openInDatabase}
              className="flex items-center gap-1 text-[10px] uppercase tracking-widest text-primary hover:text-primary/70 transition-colors"
            >
              <span aria-hidden="true" className="material-symbols-outlined text-[11px]">open_in_new</span>{t('playground.openInDocuments')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

const Playground: FC = () => {
  const { t } = useTranslation();
  const [traceMsg, setTraceMsg] = useState<Message | null>(null);
  // Piège de focus + Échap sur la modale de trace — même comportement que la fiche Documents.
  const traceRef = useFocusTrap<HTMLDivElement>(traceMsg !== null, () => setTraceMsg(null));
  const [messages, setMessages] = useState<Message[]>(() => {
    const defaultWelcome: Message = { role: 'assistant', content: t('playground.welcome'), status: 'SENT' };
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
  /** Mode expert : affiche badges RAG, distances vectorielles et métriques de latence. Off par défaut. */
  const [expertMode, setExpertMode] = useState(() =>
    localStorage.getItem('spectra_expert') === 'true');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [advisorOpen, setAdvisorOpen] = useState(false);
  const [atBottom, setAtBottom] = useState(true);
  const [regenMenuOpen, setRegenMenuOpen] = useState(false);
  const [exportMenuOpen, setExportMenuOpen] = useState(false);

  const [activeModel, setActiveModel] = useState<string>('');
  const queryClient = useQueryClient();

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
    localStorage.setItem('spectra_expert', expertMode.toString());
  }, [temperature, topP, ragEnabled, topCandidates, convEnabled, expertMode]);

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

  // Bascule optimiste : le sélecteur reflète le choix immédiatement (état local + cache
  // React Query), l'appel réseau part en arrière-plan. En cas d'échec (alias inconnu du
  // registre, API down), on restaure le modèle précédent partout.
  const handleModelSwitch = async (modelName: string) => {
    const previous = modelsData?.activeModel ?? activeModel;
    setActiveModel(modelName);
    queryClient.setQueryData(['playground-models'], (d: typeof modelsData) =>
      d ? { ...d, activeModel: modelName } : d);
    try {
      await configApi.setModelConfig({ model: modelName });
      toast.info(t('playground.modelUpdated'), {
        description: t('playground.modelUpdatedDesc', { name: modelName }),
      });
    } catch (error: any) {
      setActiveModel(previous);
      queryClient.setQueryData(['playground-models'], (d: typeof modelsData) =>
        d ? { ...d, activeModel: previous } : d);
      // 400 : alias inconnu du registre — le détail liste les modèles enregistrés.
      toast.error(t('playground.modelSwitchFailed'), {
        description: error?.response?.data?.error ?? error?.response?.data?.detail ?? error?.message,
      });
    }
  };

  const clearChat = () => {
    setMessages([{ role: 'assistant', content: t('playground.cleared'), status: 'SENT' }]);
    toast.info(t('playground.chatCleared'));
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
      lines.push(m.role === 'user' ? `## 🧑 ${t('playground.roleUser')}` : `## 🤖 ${t('playground.roleAssistant')}`, '', m.content.trim(), '');
      if (m.role === 'assistant') {
        if (m.sources?.length) {
          lines.push(`**${t('playground.mdSources')}**`);
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
          lines.push(`**${t('playground.mdPipeline')}** ${m.ragMeta.ragStrategy}${flags.length ? ` · ${flags.join(', ')}` : ''}`, '');
        }
        if (m.metrics) {
          lines.push(`_TTFT ${(m.metrics.ttftMs / 1000).toFixed(1)}s · ${(m.metrics.totalMs / 1000).toFixed(1)}s · ${m.metrics.tokens} tok${m.stopped ? ` · ${t('playground.stopped')}` : ''}_`, '');
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
    if (real.length <= 1) { toast.info(t('playground.nothingToExport')); return; }
    const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
    if (format === 'md') {
      downloadFile(`spectra-chat-${stamp}.md`, 'text/markdown', conversationToMarkdown());
    } else {
      downloadFile(`spectra-chat-${stamp}.json`, 'application/json', JSON.stringify(messages, null, 2));
    }
    toast.success(t('playground.exported', { format: format.toUpperCase() }));
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
      toast.error(t('playground.modelOffline'), { description: t('playground.modelOfflineDesc') });
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
          let msg = t('playground.queryFailedDesc');
          try { msg = JSON.parse(event.data).message ?? msg; } catch { /* ignore */ }
          toast.error(t('playground.queryFailed'), { description: msg });
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
        if (timedOut) toast.warning(t('playground.timedOut'));
        else toast.info(t('playground.generationStopped'));
        return;
      }
      toast.error(t('playground.queryFailed'), {
        description: t('playground.queryFailedDesc'),
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

  const clamp = (v: number) => Math.max(0, Math.min(2, Math.round(v * 10) / 10));
  /** Variantes de régénération : décale la température autour du réglage courant. */
  const regenVariants = [
    { label: t('playground.regenSame'),     icon: 'refresh',      temp: undefined as number | undefined },
    { label: t('playground.regenFactual'),  icon: 'target',       temp: clamp(temperature - 0.4) },
    { label: t('playground.regenCreative'), icon: 'auto_awesome', temp: clamp(temperature + 0.4) },
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
    navigator.clipboard.writeText(text).then(() => toast.success(t('playground.copied'))).catch(() => {});
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
      toast.success(next === 'UP' ? t('playground.upRecorded') : t('playground.downRecorded'));
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
            <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">{t('playground.system')}</h3>
            <div className="space-y-2">
              {[
                { label: t('playground.chatModel'), svc: chatService, warn: llmDown },
                ...(ragEnabled ? [{ label: t('playground.knowledgeBase'), svc: chromaService, warn: ragDegraded }] : []),
              ].map(({ label, svc, warn }) => (
                <div key={label} className="flex items-center justify-between">
                  <span className="text-[11px] font-label uppercase tracking-widest text-on-surface-variant">{label}</span>
                  <span className={`flex items-center gap-1.5 text-[10px] font-mono uppercase tracking-wider ${warn ? 'text-error' : 'text-primary'}`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${warn ? 'bg-error animate-pulse' : 'bg-primary'}`} />
                    {svc?.available ? t('playground.online') : t('playground.offline')}
                  </span>
                </div>
              ))}
              {llmDown && (
                <p className="text-[10px] text-error leading-relaxed pt-1">
                  <Trans i18nKey="playground.llmDownHint">
                    Chat model unreachable — start <span className="font-mono">llama-cpp-chat</span> to send messages.
                  </Trans>
                </p>
              )}
              {ragDegraded && !llmDown && (
                <p className="text-[10px] text-error leading-relaxed pt-1">
                  {t('playground.ragDegradedHint')}
                </p>
              )}
            </div>
          </div>
        )}
        <div>
          <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">{t('playground.modelParameters')}</h3>
          <div className="space-y-6">
            <div className="space-y-2">
              <div className="flex justify-between items-center">
                <Tooltip content={t('playground.temperatureTooltip')}>
                  <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant cursor-help">{t('playground.temperature')}</label>
                </Tooltip>
                <span className="text-[11px] font-mono text-primary">{temperature.toFixed(1)}</span>
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
                <Tooltip content={t('playground.topPTooltip')}>
                  <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant cursor-help">{t('playground.topP')}</label>
                </Tooltip>
                <span className="text-[11px] font-mono text-primary">{topP.toFixed(2)}</span>
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
            <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">{t('playground.activeModel')}</h3>
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
                  <p className="font-mono text-[11px] font-bold uppercase truncate">{m.name}</p>
                  {m.provenance && (
                    <p className="text-[10px] uppercase tracking-widest text-on-surface-variant mt-0.5">{m.provenance}</p>
                  )}
                </button>
              ))}
              {/* La note « effectif au prochain redémarrage » a été retirée : elle contredisait
                  le toast (llm-chat recharge le modèle automatiquement, cf. superviseur). */}
            </div>
          </div>
        )}

        <div>
          <h3 className="font-headline text-sm font-bold tracking-tight mb-4 uppercase">{t('playground.ragConfig')}</h3>
          <div className="space-y-3">
            <label className="flex items-center gap-3 cursor-pointer group">
              <input
                type="checkbox"
                checked={ragEnabled}
                onChange={(e) => {
                  const next = e.target.checked;
                  setRagEnabled(next);
                  toast.info(next ? t('playground.kbLinked') : t('playground.kbDisconnected'));
                }}
                className="sr-only peer"
              />
              <div className="w-4 h-4 border border-primary flex items-center justify-center group-hover:bg-primary/10 transition-colors peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-primary peer-focus-visible:outline-offset-2">
                {ragEnabled && <div className="w-2 h-2 bg-primary"></div>}
              </div>
              <span className="text-xs font-label uppercase tracking-widest">{t('playground.enableKb')}</span>
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
                      toast.info(next ? t('playground.convEnabled') : t('playground.convDisabled'));
                    }}
                    className="sr-only peer"
                  />
                  <div className="w-4 h-4 border border-secondary flex items-center justify-center group-hover:bg-secondary/10 transition-colors peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-secondary peer-focus-visible:outline-offset-2">
                    {convEnabled && <div className="w-2 h-2 bg-secondary"></div>}
                  </div>
                  <Tooltip content={t('playground.convTooltip')}>
                    <span className="text-xs font-label uppercase tracking-widest cursor-help">{t('playground.convHistory')}</span>
                  </Tooltip>
                </label>

                <div>
                  <button
                    className="flex items-center gap-1 text-[10px] uppercase tracking-widest text-on-surface-variant hover:text-primary transition-colors mt-1"
                    onClick={() => setShowAdvanced(v => !v)}
                  >
                    <span className="material-symbols-outlined text-[11px]">{showAdvanced ? 'expand_less' : 'expand_more'}</span>
                    {t('playground.advanced')}
                  </button>

                  {showAdvanced && (
                    <div className="mt-3 space-y-2">
                      <div className="flex justify-between items-center">
                        <Tooltip content={t('playground.topCandidatesTooltip')}>
                          <label className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant cursor-help">
                            {t('playground.topCandidates')}
                          </label>
                        </Tooltip>
                        <span className="text-[11px] font-mono text-primary">{topCandidates}</span>
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
          <label className="flex items-center gap-3 cursor-pointer group px-1 pb-1">
            <input
              type="checkbox"
              checked={expertMode}
              onChange={(e) => {
                const next = e.target.checked;
                setExpertMode(next);
                toast.info(next ? t('playground.expertModeOn') : t('playground.expertModeOff'));
              }}
              className="sr-only peer"
            />
            <div className="w-4 h-4 border border-primary flex items-center justify-center group-hover:bg-primary/10 transition-colors peer-focus-visible:outline peer-focus-visible:outline-2 peer-focus-visible:outline-primary peer-focus-visible:outline-offset-2">
              {expertMode && <div className="w-2 h-2 bg-primary"></div>}
            </div>
            <Tooltip content={t('playground.expertModeHint')}>
              <span className="text-xs font-label uppercase tracking-widest cursor-help">{t('playground.expertMode')}</span>
            </Tooltip>
          </label>
          <button
            onClick={() => setAdvisorOpen(true)}
            className="w-full py-3 px-4 border border-primary/30 text-primary text-[11px] font-headline uppercase tracking-widest hover:bg-primary/5 transition-colors flex items-center justify-center gap-2"
          >
            <span className="material-symbols-outlined text-sm">psychology</span>
            {t('playground.ragAdvisor')}
          </button>
          <div className="relative">
            <button
              onClick={() => setExportMenuOpen(o => !o)}
              aria-haspopup="menu" aria-expanded={exportMenuOpen}
              className="w-full py-3 px-4 border border-outline-variant/40 text-on-surface-variant text-[11px] font-headline uppercase tracking-widest hover:border-primary/40 hover:text-primary transition-colors flex items-center justify-center gap-2"
            >
              <span className="material-symbols-outlined text-sm">download</span>
              {t('playground.exportConversation')}
            </button>
            {exportMenuOpen && (
              <>
                <button type="button" aria-hidden="true" tabIndex={-1}
                  className="fixed inset-0 z-10 cursor-default" onClick={() => setExportMenuOpen(false)} />
                <div role="menu"
                  className="absolute left-0 right-0 bottom-full mb-1 z-20 bg-surface-container-high border border-outline-variant/30 shadow-lg py-1 animate-in fade-in slide-in-from-bottom-1">
                  <button type="button" role="menuitem" onClick={() => exportConversation('md')}
                    className="w-full flex items-center gap-2 px-3 py-2 text-left text-[11px] uppercase tracking-widest text-on-surface-variant hover:bg-primary/10 hover:text-primary transition-colors">
                    <span aria-hidden="true" className="material-symbols-outlined text-[14px]">description</span>{t('playground.exportMd')}
                  </button>
                  <button type="button" role="menuitem" onClick={() => exportConversation('json')}
                    className="w-full flex items-center gap-2 px-3 py-2 text-left text-[11px] uppercase tracking-widest text-on-surface-variant hover:bg-primary/10 hover:text-primary transition-colors">
                    <span aria-hidden="true" className="material-symbols-outlined text-[14px]">data_object</span>{t('playground.exportJson')}
                  </button>
                </div>
              </>
            )}
          </div>
          <button
            onClick={clearChat}
            className="w-full py-3 px-4 border border-error/30 text-error text-[11px] font-headline uppercase tracking-widest hover:bg-error/5 transition-colors flex items-center justify-center gap-2"
          >
            <span className="material-symbols-outlined text-sm">delete_sweep</span>
            {t('playground.clearChat')}
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
          aria-label={t('playground.conversationAria')}
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

                <p className="font-label text-[11px] uppercase tracking-[0.1em] text-on-surface-variant mb-3">
                  {msg.role === 'user' ? t('playground.roleUser') : t('playground.roleAssistant')}
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
                    <p className="font-label text-[10px] uppercase tracking-widest text-outline mb-1">{t('playground.sourcesTitle', { count: msg.sources.length })}</p>
                    {msg.sources.map((src, j) => <SourceItem key={j} src={src} expert={expertMode} />)}
                  </div>
                )}

                {expertMode && msg.ragMeta && msg.status === 'SENT' && msg.role === 'assistant' && (
                  <RagBadges meta={msg.ragMeta} onShowTrace={() => setTraceMsg(msg)} />
                )}

                {/* Pied de bulle : métriques + feedback (toujours) + copy/regenerate (survol) */}
                {msg.role === 'assistant' && msg.status === 'SENT' && msg.content && (
                  <div className="mt-3 flex items-center justify-between gap-3">
                    {msg.metrics && expertMode ? (
                      <div className="flex items-center gap-3 text-[10px] font-mono text-outline">
                        <span title={t('playground.ttftTitle')}>TTFT {(msg.metrics.ttftMs / 1000).toFixed(1)}s</span>
                        <span title={t('playground.totalTitle')}>{(msg.metrics.totalMs / 1000).toFixed(1)}s</span>
                        <span title={t('playground.tokensTitle')}>{msg.metrics.tokens} tok</span>
                        {msg.stopped && (
                          <span className="text-error font-bold uppercase tracking-wider" title={t('playground.stoppedTitle')}>
                            {t('playground.stopped')}
                          </span>
                        )}
                      </div>
                    ) : msg.stopped ? (
                      <span className="text-[10px] font-mono text-error font-bold uppercase tracking-wider" title={t('playground.stoppedTitle')}>
                        {t('playground.stopped')}
                      </span>
                    ) : <span />}

                    <div className="flex items-center gap-1">
                      <button type="button" onClick={() => sendFeedback(i, 'UP')}
                        aria-label={t('playground.goodAnswer')} aria-pressed={msg.feedback === 'UP'}
                        className={`material-symbols-outlined text-[14px] px-1 transition-colors ${msg.feedback === 'UP' ? 'text-primary' : 'text-outline hover:text-primary'}`}>thumb_up</button>
                      <button type="button" onClick={() => sendFeedback(i, 'DOWN')}
                        aria-label={t('playground.badAnswer')} aria-pressed={msg.feedback === 'DOWN'}
                        className={`material-symbols-outlined text-[14px] px-1 transition-colors ${msg.feedback === 'DOWN' ? 'text-error' : 'text-outline hover:text-error'}`}>thumb_down</button>

                      <span className="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
                        <button type="button" onClick={() => copyAnswer(msg.content)} aria-label={t('playground.copy')}
                          className="flex items-center gap-1 text-[10px] uppercase tracking-widest text-outline hover:text-primary transition-colors px-1.5 py-0.5">
                          <span aria-hidden="true" className="material-symbols-outlined text-[13px]">content_copy</span>{t('playground.copy')}
                        </button>
                        {i === lastAssistantIdx && (
                          <div className="relative">
                            <button type="button" onClick={() => setRegenMenuOpen(o => !o)} disabled={isTyping}
                              aria-label={t('playground.regenerate')} aria-haspopup="menu" aria-expanded={regenMenuOpen}
                              className="flex items-center gap-1 text-[10px] uppercase tracking-widest text-outline hover:text-primary transition-colors px-1.5 py-0.5 disabled:opacity-40">
                              <span aria-hidden="true" className="material-symbols-outlined text-[13px]">refresh</span>{t('playground.regenerate')}
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
                                      className="w-full flex items-center gap-2 px-3 py-1.5 text-left text-[11px] uppercase tracking-widest text-on-surface-variant hover:bg-primary/10 hover:text-primary transition-colors">
                                      <span aria-hidden="true" className="material-symbols-outlined text-[13px]">{v.icon}</span>
                                      <span className="flex-1">{v.label}</span>
                                      {v.temp !== undefined && <span className="font-mono text-[10px] text-outline">{v.temp.toFixed(1)}</span>}
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
                    <button type="button" onClick={() => editMessage(i)} disabled={isTyping} aria-label={t('playground.edit')}
                      className="flex items-center gap-1 text-[10px] uppercase tracking-widest text-outline hover:text-secondary transition-colors px-1.5 py-0.5 disabled:opacity-40">
                      <span aria-hidden="true" className="material-symbols-outlined text-[13px]">edit</span>{t('playground.edit')}
                    </button>
                    {msg.status === 'ERROR' && (
                      <button type="button" onClick={() => regenerateLast()} disabled={isTyping} aria-label={t('playground.retry')}
                        className="flex items-center gap-1 text-[10px] uppercase tracking-widest text-error hover:text-error/80 transition-colors px-1.5 py-0.5 disabled:opacity-40">
                        <span aria-hidden="true" className="material-symbols-outlined text-[13px]">replay</span>{t('playground.retry')}
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
                <span className="text-[10px] uppercase tracking-widest text-outline">{t('playground.processing')}</span>
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
            aria-label={t('playground.scrollAria')}
            className="absolute bottom-28 right-6 z-10 flex items-center gap-1 bg-surface-container-high border border-outline-variant/30 text-on-surface-variant hover:text-primary px-2.5 py-1.5 shadow-lg text-[10px] uppercase tracking-widest transition-colors animate-in fade-in slide-in-from-bottom-2"
          >
            <span aria-hidden="true" className={`material-symbols-outlined text-[14px] ${isTyping ? 'text-primary animate-bounce' : ''}`}>arrow_downward</span>
            {isTyping ? t('playground.scrollNew') : t('playground.scrollLatest')}
          </button>
        )}

        <div className="p-8 border-t border-outline-variant/10">
          {convEnabled && messages.filter(m => m.status === 'SENT').length > 1 && (
            <p className="text-[10px] font-label uppercase tracking-widest text-secondary mb-2 flex items-center gap-1">
              <span className="material-symbols-outlined text-[11px]">forum</span>
              {t('playground.conversationalBar', { count: messages.filter(m => m.status === 'SENT').length })}
            </p>
          )}
          <div className="flex items-end gap-3 bg-surface-container-lowest border border-outline-variant/20 p-2">
            <label htmlFor="chat-input" className="sr-only">{t('playground.messageLabel')}</label>
            <textarea
              id="chat-input"
              ref={textareaRef}
              rows={1}
              className="flex-1 bg-transparent border-none focus:ring-0 focus:outline-none text-sm font-body px-4 py-2 resize-none max-h-40 custom-scrollbar"
              placeholder={llmDown ? t('playground.inputPlaceholderOffline') : t('playground.inputPlaceholder')}
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
                aria-label={t('playground.stop')}
                className="bg-error/90 text-on-error p-2 transition-all hover:bg-error flex items-center justify-center"
              >
                <span aria-hidden="true" className="material-symbols-outlined">stop</span>
              </button>
            ) : (
              <button
                type="button"
                onClick={handleSend}
                disabled={!input.trim() || llmDown}
                aria-label={t('playground.send')}
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
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 sm:p-6 bg-black/60 backdrop-blur-sm animate-in fade-in duration-300"
          onClick={() => setTraceMsg(null)}
        >
          <div
            ref={traceRef}
            tabIndex={-1}
            role="dialog"
            aria-modal="true"
            aria-label={t('playground.traceTitle')}
            onClick={e => e.stopPropagation()}
            className="bg-surface-container border border-outline-variant/30 shadow-2xl w-full max-w-4xl max-h-full flex flex-col rounded-xl overflow-hidden outline-none animate-in zoom-in-95 duration-300"
          >
            <header className="px-6 py-4 border-b border-outline-variant/10 flex items-center justify-between bg-surface-container-high shrink-0">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-lg bg-primary/20 text-primary flex items-center justify-center">
                  <span className="material-symbols-outlined text-sm">insights</span>
                </div>
                <div>
                  <h2 className="font-headline font-bold text-lg text-on-surface">{t('playground.traceTitle')}</h2>
                  <p className="text-[11px] uppercase tracking-widest text-on-surface-variant">{t('playground.traceSubtitle')}</p>
                </div>
              </div>
              <button
                onClick={() => setTraceMsg(null)}
                className="w-8 h-8 flex items-center justify-center text-on-surface-variant hover:text-on-surface hover:bg-surface-container-highest transition-colors rounded-full"
                aria-label={t('playground.traceClose')}
              >
                <span className="material-symbols-outlined text-sm">close</span>
              </button>
            </header>

            <div className="flex-1 overflow-y-auto p-6 space-y-8 custom-scrollbar">
              <div className="space-y-3">
                <h3 className="font-headline font-bold text-sm text-primary uppercase tracking-widest flex items-center gap-2">
                  <span className="material-symbols-outlined text-base">route</span>
                  {t('playground.strategyApplied')}
                </h3>
                <div className="bg-surface-container-low border border-outline-variant/10 rounded-lg p-4">
                  <div className="flex flex-col md:flex-row gap-6 md:items-center justify-between">
                    <div>
                       <p className="text-xl font-headline font-bold text-on-surface">{traceMsg.ragMeta?.ragStrategy}</p>
                       <p className="text-xs text-on-surface-variant mt-1">
                         {traceMsg.ragMeta?.ragStrategy === 'AGENTIC' ? t('playground.stratAgentic') :
                          traceMsg.ragMeta?.ragStrategy === 'STANDARD' ? t('playground.stratStandard') :
                          t('playground.stratDirect')}
                       </p>
                    </div>
                    {traceMsg.ragMeta?.ragStrategy === 'AGENTIC' && traceMsg.ragMeta?.agenticIterations && (
                      <div className="text-right">
                        <p className="text-2xl font-mono text-primary">{traceMsg.ragMeta?.agenticIterations}</p>
                        <p className="text-[11px] uppercase tracking-widest text-on-surface-variant mt-1">{t('playground.iterations')}</p>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              <div className="space-y-3">
                <h3 className="font-headline font-bold text-sm text-secondary uppercase tracking-widest flex items-center gap-2">
                  <span className="material-symbols-outlined text-base">filter_alt</span>
                  {t('playground.optimizationsTriggered')}
                </h3>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                   {[
                      { active: traceMsg.ragMeta?.hybridSearchApplied, key: 'hybrid' },
                      { active: traceMsg.ragMeta?.rerankApplied, key: 'rerank' },
                      { active: traceMsg.ragMeta?.multiQueryApplied, key: 'multiQuery' },
                      { active: traceMsg.ragMeta?.compressionApplied, key: 'compression' },
                      { active: traceMsg.ragMeta?.semanticDedupApplied, key: 'dedup' },
                      { active: traceMsg.ragMeta?.correctiveApplied, key: 'corrective' },
                   ].map(opt => (
                     <div key={opt.key} className={`p-4 rounded-lg border ${opt.active ? 'bg-secondary/10 border-secondary/30' : 'bg-surface-container-low border-outline-variant/10 opacity-50'}`}>
                        <div className="flex items-center gap-2 mb-2">
                          <span className={`material-symbols-outlined text-sm ${opt.active ? 'text-secondary' : 'text-on-surface-variant'}`}>
                            {opt.active ? 'check_circle' : 'cancel'}
                          </span>
                          <span className={`font-bold text-xs ${opt.active ? 'text-secondary' : 'text-on-surface-variant'}`}>{t(`playground.opts.${opt.key}.label`)}</span>
                        </div>
                        <p className="text-[11px] text-on-surface-variant leading-relaxed">{t(`playground.opts.${opt.key}.desc`)}</p>
                     </div>
                   ))}
                </div>
              </div>

              {traceMsg.sources && traceMsg.sources.length > 0 && (
                <div className="space-y-3">
                  <h3 className="font-headline font-bold text-sm text-primary uppercase tracking-widest flex items-center gap-2">
                    <span className="material-symbols-outlined text-base">format_list_numbered</span>
                    {t('playground.finalContext')}
                  </h3>
                  <div className="space-y-2">
                     {traceMsg.sources.map((src, i) => {
                       const pct = typeof src.distance === 'number' ? Math.max(0, Math.min(100, Math.round((1 - src.distance) * 100))) : null;
                       return (
                        <div key={i} className="bg-surface-container-low border border-outline-variant/10 p-3 rounded text-xs space-y-1">
                           <div className="flex items-center justify-between">
                             <span className="font-bold text-on-surface break-all">{src.sourceFile}</span>
                             {pct !== null && (
                               <span className="text-[11px] bg-primary/20 text-primary px-1.5 rounded">{t('playground.relevancePct', { pct })}</span>
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
