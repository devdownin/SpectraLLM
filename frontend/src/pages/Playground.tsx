import { useState, useEffect, useRef } from 'react';
import type { FC } from 'react';
import { toast } from 'sonner';
import { queryApi, configApi, fineTuningApi } from '../services/api';
import Tooltip from '../components/Tooltip';

interface Source {
  preview: string;
  sourceFile: string;
  distance: number;
}

interface Message {
  role: 'user' | 'assistant';
  content: string;
  sources?: Source[];
  status?: 'PENDING' | 'SENT' | 'ERROR' | 'STREAMING';
}

const Playground: FC = () => {
  const [messages, setMessages] = useState<Message[]>(() => {
    const saved = localStorage.getItem('spectra_chat_history');
    return saved ? JSON.parse(saved) : [
      { role: 'assistant', content: 'Welcome to the Spectra Playground. I am ready to answer questions based on your ingested documents. How can I help you today?', status: 'SENT' }
    ];
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
  const [showAdvanced, setShowAdvanced] = useState(false);

  const [activeModel, setActiveModel] = useState<string>('');
  const [availableModels, setAvailableModels] = useState<Array<{ name: string; provenance?: string }>>([]);

  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    localStorage.setItem('spectra_chat_history', JSON.stringify(messages));
  }, [messages]);

  useEffect(() => {
    localStorage.setItem('spectra_temp', temperature.toString());
    localStorage.setItem('spectra_top_p', topP.toString());
    localStorage.setItem('spectra_rag', ragEnabled.toString());
    localStorage.setItem('spectra_top_candidates', topCandidates.toString());
  }, [temperature, topP, ragEnabled, topCandidates]);

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
      toast.info('Registre mis à jour', {
        description: `Modèle "${modelName}" actif au prochain redémarrage de llama-cpp-chat.`,
      });
    } catch {
      toast.error('Erreur lors du changement de modèle');
    }
  };

  const clearChat = () => {
    setMessages([{ role: 'assistant', content: 'Discussion cleared. System ready.', status: 'SENT' }]);
    toast.info('Chat history cleared');
  };

  const handleSend = async () => {
    if (!input.trim() || isTyping) return;

    // Cancel any in-flight stream
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    const currentInput = input;
    setMessages(prev => [...prev, { role: 'user', content: currentInput, status: 'PENDING' }]);
    setInput('');
    setIsTyping(true);

    // Placeholder assistant message that will be updated token by token
    setMessages(prev => [...prev, { role: 'assistant', content: '', status: 'STREAMING' }]);

    try {
      let sources: Source[] = [];

      for await (const event of queryApi.queryStream(currentInput, ragEnabled, controller.signal, topCandidates)) {
        if (event.type === 'sources') {
          try { sources = JSON.parse(event.data); } catch { /* ignore */ }
        } else if (event.type === 'token') {
          setMessages(prev => {
            const updated = [...prev];
            const last = updated.findLast(m => m.role === 'assistant');
            if (last) last.content += event.data;
            return [...updated];
          });
        } else if (event.type === 'done') {
          setMessages(prev => {
            const updated = [...prev];
            const lastUser = updated.findLast(m => m.role === 'user' && m.content === currentInput);
            if (lastUser) lastUser.status = 'SENT';
            const last = updated.findLast(m => m.role === 'assistant');
            if (last) { last.status = 'SENT'; last.sources = sources; }
            return [...updated];
          });
        } else if (event.type === 'error') {
          let msg = 'Spectra core is currently unreachable or timed out.';
          try { msg = JSON.parse(event.data).message ?? msg; } catch { /* ignore */ }
          toast.error('Query Uplink Failed', { description: msg });
          setMessages(prev => {
            const updated = [...prev];
            const last = updated.findLast(m => m.role === 'assistant');
            if (last) last.status = 'ERROR';
            return [...updated];
          });
        }
      }
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') return;
      toast.error('Query Uplink Failed', {
        description: 'Spectra core is currently unreachable or timed out.'
      });
      setMessages(prev => {
        const updated = [...prev];
        const lastUser = updated.findLast(m => m.role === 'user' && m.content === currentInput);
        if (lastUser) lastUser.status = 'ERROR';
        const last = updated.findLast(m => m.role === 'assistant');
        if (last && last.content === '') updated.splice(updated.lastIndexOf(last), 1);
        return [...updated];
      });
    } finally {
      setIsTyping(false);
    }
  };

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
            <label className="flex items-center gap-3 cursor-pointer group" onClick={() => {
              const next = !ragEnabled;
              setRagEnabled(next);
              toast.info(next ? 'Knowledge Base Linked' : 'Knowledge Base Disconnected');
            }}>
              <div className="w-4 h-4 border border-primary flex items-center justify-center group-hover:bg-primary/10 transition-colors">
                {ragEnabled && <div className="w-2 h-2 bg-primary"></div>}
              </div>
              <span className="text-xs font-label uppercase tracking-widest">Enable Knowledge Base</span>
            </label>

            {ragEnabled && (
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
                      <Tooltip content="Nombre de candidats envoyés au re-ranker (plus élevé = meilleure couverture, plus lent).">
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
            )}
          </div>
        </div>

        <div className="pt-8 border-t border-outline-variant/10">
          <button
            onClick={clearChat}
            className="w-full py-3 px-4 border border-error/30 text-error text-[10px] font-headline uppercase tracking-widest hover:bg-error/5 transition-colors flex items-center justify-center gap-2"
          >
            <span className="material-symbols-outlined text-sm">delete_sweep</span>
            Clear Chat History
          </button>
        </div>
      </aside>

      <div className="flex-1 flex flex-col bg-surface-container overflow-hidden">
        <div className="flex-1 overflow-y-auto p-8 space-y-8 custom-scrollbar">
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
                <p className="text-sm font-body leading-relaxed whitespace-pre-wrap">
                  {msg.content}
                  {msg.status === 'STREAMING' && (
                    <span className="inline-block w-1.5 h-3.5 bg-primary ml-0.5 animate-pulse align-middle" />
                  )}
                </p>

                {msg.sources && msg.sources.length > 0 && (
                  <div className="mt-4 pt-4 border-t border-outline-variant/20 space-y-1">
                    <p className="font-label text-[9px] uppercase tracking-widest text-outline mb-2">Sources</p>
                    {msg.sources.map((src, j) => (
                      <div key={j} className="flex items-start gap-2">
                        <span className="material-symbols-outlined text-[12px] text-primary mt-0.5 shrink-0">article</span>
                        <span className="font-mono text-[9px] text-on-surface-variant truncate" title={src.preview}>
                          {src.sourceFile}
                        </span>
                      </div>
                    ))}
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
        </div>

        <div className="p-8 border-t border-outline-variant/10">
          <div className="flex items-center gap-4 bg-surface-container-lowest border border-outline-variant/20 p-2">
            <input
              type="text"
              className="flex-1 bg-transparent border-none focus:ring-0 text-sm font-body px-4"
              placeholder={isTyping ? "Spectra is thinking..." : "Inject query (Cmd+Enter)..."}
              value={input}
              disabled={isTyping}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                  handleSend();
                }
              }}
            />
            <button
              className={`bg-primary text-on-primary-fixed p-2 transition-all ${isTyping ? 'opacity-50 grayscale' : 'hover:opacity-90'}`}
              onClick={handleSend}
              disabled={isTyping}
            >
              <span className="material-symbols-outlined">send</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default Playground;
