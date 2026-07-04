import type { FC } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { modelsHubApi } from '../services/api';
import Skeleton from '../components/Skeleton';
import { useState, useEffect, useRef, useCallback } from 'react';
import { toast } from 'sonner';

const ModelHub: FC = () => {
  const [installingModels, setInstallingModels] = useState<Record<string, number>>({});
  const [installedModels, setInstalledModels] = useState<string[]>([]);
  const [autoActivate, setAutoActivate] = useState(false);
  const activeEventSources = useRef<Map<string, EventSource>>(new Map());
  // autoActivate lu via ref pour ne pas recréer subscribeProgress (et donc l'effet de reprise).
  const autoActivateRef = useRef(autoActivate);
  autoActivateRef.current = autoActivate;

  const [filter, setFilter] = useState('All');
  const [limit, setLimit] = useState(12);
  const [simulation, setSimulation] = useState<{memory?: string, ram?: string, cpuCores?: number}>({});
  const [isSimulating, setIsSimulating] = useState(false);

  const { data: recommendations, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['model-recommendations', simulation, limit],
    queryFn: () => modelsHubApi.getRecommendations({ limit, ...simulation }).then(res => res.data),
  });

  // Persiste les téléchargements en cours (nom → dernier %) pour pouvoir reprendre le suivi
  // après une navigation / un rechargement de page : le sink SSE côté serveur rejoue le %.
  const persistInstalling = (models: Record<string, number>) => {
    try {
      const active = Object.fromEntries(Object.entries(models).filter(([, p]) => p < 100));
      if (Object.keys(active).length) sessionStorage.setItem('modelhub-installing', JSON.stringify(active));
      else sessionStorage.removeItem('modelhub-installing');
    } catch { /* sessionStorage indisponible */ }
  };

  /** Ouvre (ou ré-ouvre) le flux de progression d'un modèle et branche les handlers. */
  const subscribeProgress = useCallback((modelName: string) => {
    if (activeEventSources.current.has(modelName)) return; // déjà suivi
    const eventSource = modelsHubApi.getProgressSource(modelName);
    activeEventSources.current.set(modelName, eventSource);
    const cleanupSource = () => {
      activeEventSources.current.get(modelName)?.close();
      activeEventSources.current.delete(modelName);
    };
    eventSource.onmessage = (event) => {
      const progress = parseInt(event.data);
      if (isNaN(progress)) return;
      setInstallingModels(prev => { const next = { ...prev, [modelName]: progress }; persistInstalling(next); return next; });
      if (progress >= 100) {
        cleanupSource();
        setInstallingModels(prev => { const next = { ...prev }; delete next[modelName]; persistInstalling(next); return next; });
        setInstalledModels(prev => prev.includes(modelName) ? prev : [...prev, modelName]);
        // Rafraîchir les recommandations pour que `model.installed` reflète l'installation serveur.
        refetch();
        toast.success(`Model "${modelName}" downloaded`, {
          description: autoActivateRef.current
            ? 'Activated — restart llm-chat to load it: docker compose restart llm-chat'
            : 'Saved to the registry. Activate it in the Playground, then restart llm-chat.',
          duration: 8000,
        });
      }
    };
    eventSource.onerror = () => {
      cleanupSource();
      setInstallingModels(prev => { const next = { ...prev }; delete next[modelName]; persistInstalling(next); return next; });
      toast.error(`Progress tracking unavailable for "${modelName}"`, {
        description: 'The download may still be running. Check the logs: docker compose logs spectra-api',
      });
    };
  }, [refetch]);

  // Reprise au montage : ré-abonne les téléchargements en cours mémorisés (navigation / reload).
  useEffect(() => {
    let persisted: Record<string, number> = {};
    try { persisted = JSON.parse(sessionStorage.getItem('modelhub-installing') || '{}'); } catch { /* ignore */ }
    const names = Object.keys(persisted);
    if (names.length) {
      setInstallingModels(persisted); // affiche le dernier % connu immédiatement
      names.forEach(subscribeProgress); // le sink serveur rejoue la progression courante
    }
    const sources = activeEventSources.current;
    return () => { sources.forEach(es => es.close()); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const installMutation = useMutation({
    mutationFn: ({ modelName, quant }: { modelName: string; quant?: string }) =>
      modelsHubApi.installModel(modelName, quant, autoActivate),
    onSuccess: (_, variables) => {
      setInstallingModels(prev => { const next = { ...prev, [variables.modelName]: 0 }; persistInstalling(next); return next; });
      subscribeProgress(variables.modelName);
    },
    onError: (error: any) => {
      toast.error('Failed to start the download', {
        description: error?.response?.data?.message ?? error.message,
      });
    }
  });

  const handleInstall = (modelName: string, quant?: string) => {
    installMutation.mutate({ modelName, quant });
  };

  const filteredModels = recommendations?.models?.filter((m: any) => {
    if (filter === 'All') return true;
    if (filter === 'Runnable') return m.fit_level !== 'Too Tight';
    return m.fit_level === filter;
  });

  if (isLoading) {
    return (
      <div className="p-8 space-y-6">
        <header>
          <h1 className="text-3xl font-black text-primary font-headline tracking-tight uppercase">Model Hub</h1>
          <p className="text-outline mt-2">Hardware optimization by llmfit</p>
        </header>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {[...Array(6)].map((_, i) => (
            <Skeleton key={i} className="h-64 w-full" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="p-8 space-y-8 animate-in fade-in duration-700">
      <header className="flex flex-col md:flex-row md:justify-between md:items-end gap-4">
        <div>
          <div className="flex items-center gap-3 mb-1">
            <span className="material-symbols-outlined text-primary">hub</span>
            <h1 className="text-3xl font-black text-primary font-headline tracking-tight uppercase">Model Hub</h1>
          </div>
          <p className="text-outline max-w-2xl">
            Discover the LLM models best suited to your current or simulated hardware configuration.
            Powered by <span className="text-primary font-bold">llmfit</span>.
          </p>
        </div>

        <div className="flex flex-wrap gap-3">
          <div className="flex items-center gap-2 bg-surface-container-low px-3 py-1 border border-outline-variant/20">
            <span className="text-[10px] font-black uppercase tracking-widest text-outline">Show</span>
            <select
              value={limit}
              onChange={(e) => setLimit(parseInt(e.target.value))}
              className="bg-transparent text-xs font-bold text-primary focus:outline-none cursor-pointer"
            >
              {[3, 6, 9, 12, 15, 18, 21, 24].map(n => (
                <option key={n} value={n}>{n}</option>
              ))}
            </select>
          </div>

          <div className="flex items-center gap-2 bg-surface-container-low px-3 py-1 border border-outline-variant/20">
            <span className="text-[10px] font-black uppercase tracking-widest text-outline">Filter</span>
            <select
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="bg-transparent text-xs font-bold text-primary focus:outline-none cursor-pointer"
            >
              <option value="All">All</option>
              <option value="Perfect">Perfect</option>
              <option value="Good">Good</option>
              <option value="Marginal">Marginal</option>
              <option value="Runnable">Executables</option>
            </select>
          </div>

          <label className="flex items-center gap-2 bg-surface-container-low px-3 py-1 border border-outline-variant/20 cursor-pointer">
            <input
              type="checkbox"
              checked={autoActivate}
              onChange={(e) => setAutoActivate(e.target.checked)}
              className="accent-primary"
            />
            <span className="text-[10px] font-black uppercase tracking-widest text-outline">Auto-activation</span>
          </label>

          <button
            onClick={() => setIsSimulating(!isSimulating)}
            className={`flex items-center gap-2 px-4 py-2 transition-colors border border-outline-variant/20 ${isSimulating ? 'bg-primary text-on-primary' : 'bg-surface-container-high text-primary hover:bg-surface-variant'}`}
          >
            <span className="material-symbols-outlined text-sm">settings_input_component</span>
            <span className="font-headline uppercase tracking-widest text-[10px] font-bold">Simulation</span>
          </button>

          <button
            onClick={() => refetch()}
            className="flex items-center gap-2 px-4 py-2 bg-surface-container-high hover:bg-surface-variant text-primary transition-colors border border-outline-variant/20"
          >
            <span className={`material-symbols-outlined text-sm ${isFetching ? 'animate-spin' : ''}`}>refresh</span>
            <span className="font-headline uppercase tracking-widest text-[10px] font-bold">Refresh</span>
          </button>
        </div>
      </header>

      {isSimulating && (
        <section className="bg-primary/5 p-6 border border-primary/20 animate-in slide-in-from-top-4 duration-300">
          <div className="flex items-center gap-3 mb-4">
             <span className="material-symbols-outlined text-primary">simulation</span>
             <h2 className="text-sm font-black uppercase tracking-widest text-primary font-headline">Hardware Simulator</h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <div className="space-y-2">
              <label className="text-[10px] uppercase tracking-wider text-outline font-bold">VRAM GPU (ex: 12G, 24G)</label>
              <input
                type="text"
                placeholder="Auto"
                value={simulation.memory || ''}
                onChange={(e) => setSimulation({...simulation, memory: e.target.value})}
                className="w-full bg-surface-container-lowest border border-outline-variant/20 p-2 text-sm focus:border-primary outline-none"
              />
            </div>
            <div className="space-y-2">
              <label className="text-[10px] uppercase tracking-wider text-outline font-bold">System RAM (e.g. 32G, 64G)</label>
              <input
                type="text"
                placeholder="Auto"
                value={simulation.ram || ''}
                onChange={(e) => setSimulation({...simulation, ram: e.target.value})}
                className="w-full bg-surface-container-lowest border border-outline-variant/20 p-2 text-sm focus:border-primary outline-none"
              />
            </div>
            <div className="space-y-2">
              <label className="text-[10px] uppercase tracking-wider text-outline font-bold">CPU Cores</label>
              <input
                type="number"
                placeholder="Auto"
                value={simulation.cpuCores || ''}
                onChange={(e) => setSimulation({...simulation, cpuCores: e.target.value ? parseInt(e.target.value) : undefined})}
                className="w-full bg-surface-container-lowest border border-outline-variant/20 p-2 text-sm focus:border-primary outline-none"
              />
            </div>
          </div>
          <div className="mt-4 flex justify-end gap-3">
            <button
              onClick={() => { setSimulation({}); setIsSimulating(false); }}
              className="text-[10px] font-black uppercase tracking-widest text-outline hover:text-primary transition-colors"
            >
              Réinitialiser
            </button>
          </div>
        </section>
      )}

      {recommendations?.system && (
        <section className="bg-surface-container-low p-4 border border-outline-variant/10 flex flex-wrap gap-8 items-center">
          <div className="flex items-center gap-3">
            <span className="material-symbols-outlined text-outline">cpu</span>
            <div>
              <div className="text-[10px] uppercase tracking-wider text-outline font-bold">CPU</div>
              <div className="text-sm font-medium">{recommendations.system.cpu_name} ({recommendations.system.cpu_cores} cores)</div>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <span className="material-symbols-outlined text-outline">memory</span>
            <div>
              <div className="text-[10px] uppercase tracking-wider text-outline font-bold">RAM</div>
              <div className="text-sm font-medium">{recommendations.system.total_ram_gb.toFixed(1)} GB ({recommendations.system.available_ram_gb.toFixed(1)} GB dispos)</div>
            </div>
          </div>
          {(recommendations.system.has_gpu || simulation.memory) && (
            <div className="flex items-center gap-3">
              <span className="material-symbols-outlined text-secondary">memory_alt</span>
              <div>
                <div className="text-[10px] uppercase tracking-wider text-secondary font-bold">GPU</div>
                <div className="text-sm font-medium">{recommendations.system.gpu_name || 'Simulated'} ({recommendations.system.gpu_vram_gb?.toFixed(1) || simulation.memory} VRAM)</div>
              </div>
            </div>
          )}
        </section>
      )}

      {/* Post-install Docker restart reminder */}
      {installedModels.length > 0 && (
        <div className="bg-primary/5 border border-primary/30 p-4 flex items-start gap-3">
          <span className="material-symbols-outlined text-primary text-sm mt-0.5 shrink-0">info</span>
          <div className="space-y-1">
            <p className="text-[10px] font-label font-bold uppercase tracking-widest text-primary">
              Model(s) downloaded — restart required
            </p>
            <p className="text-[9px] text-on-surface-variant leading-relaxed">
              The GGUF file was copied to <code className="font-mono bg-surface-container px-1">data/models/</code>.
              To have <strong>llm-chat</strong> serve this model, update <code className="font-mono bg-surface-container px-1">.env</code> then restart:
            </p>
            <code className="block font-mono text-[9px] bg-surface-container px-2 py-1 text-primary mt-1">
              docker compose restart llm-chat
            </code>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
        {filteredModels?.map((model: any) => (
          <div key={model.name} className="group relative bg-surface-container-lowest border border-outline-variant/10 hover:border-primary/30 transition-all flex flex-col h-full shadow-sm hover:shadow-md">
            <div className="p-6 flex-1">
              <div className="flex justify-between items-start mb-4">
                <div className="px-2 py-0.5 bg-primary/10 text-primary text-[10px] font-black uppercase tracking-widest border border-primary/20">
                  {model.parameter_count}
                </div>
                <div className="flex flex-col items-end">
                  <div className="text-2xl font-black text-primary font-headline leading-none">
                    {Math.round(model.score)}
                  </div>
                  <div className="text-[8px] uppercase tracking-tighter text-outline font-bold">FIT SCORE</div>
                </div>
              </div>

              <h3 className="text-lg font-bold mb-1 group-hover:text-primary transition-colors break-words">{model.name}</h3>
              <p className="text-xs text-outline mb-4">{model.provider} • {model.use_case}</p>

              <div className="space-y-3 mt-6">
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">Mode</span>
                  <span className={`font-bold uppercase tracking-wider ${model.run_mode === 'GPU' ? 'text-secondary' : 'text-primary'}`}>
                    {model.run_mode}
                  </span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">Quantification</span>
                  <span className="font-medium">{model.best_quant}</span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">Estimated speed</span>
                  <span className="font-medium">{model.estimated_tps?.toFixed(1)} tok/s</span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">Required memory</span>
                  <span className="font-medium">{model.memory_required_gb?.toFixed(1)} GB</span>
                </div>
              </div>

              <div className="mt-6 pt-6 border-t border-outline-variant/10">
                 <div className="text-[10px] font-black uppercase tracking-widest text-outline mb-2">Capabilities</div>
                 <div className="flex flex-wrap gap-2">
                    {model.score_components && Object.entries(model.score_components).map(([key, val]: [string, any]) => (
                      <div key={key} className="flex flex-col">
                         <div className="h-1 w-12 bg-surface-variant rounded-full overflow-hidden">
                            <div className="h-full bg-primary" style={{ width: `${val}%` }}></div>
                         </div>
                         <span className="text-[8px] uppercase mt-1 text-outline-variant">{key === 'fit' ? 'fit' : key === 'quality' ? 'quality' : key === 'speed' ? 'vitesse' : key}</span>
                      </div>
                    ))}
                 </div>
              </div>
            </div>

            <div className="relative">
              {installingModels[model.name] !== undefined && installingModels[model.name] < 100 && (
                <div className="absolute top-0 left-0 h-1 bg-secondary transition-all duration-300" style={{ width: `${installingModels[model.name]}%` }}></div>
              )}
              {/* installedModels donne un retour immédiat en attendant le refetch des recommandations. */}
              {(() => {
                const isInstalled = model.installed || installedModels.includes(model.name);
                const isInstalling = installingModels[model.name] !== undefined;
                return (
              <button
                onClick={() => handleInstall(model.name, model.best_quant)}
                disabled={isInstalled || isInstalling}
                className={`w-full py-4 font-headline uppercase tracking-[0.2em] text-[11px] font-black flex items-center justify-center gap-2 transition-all ${
                  isInstalled
                  ? 'bg-surface-variant text-outline cursor-default'
                  : isInstalling
                    ? 'bg-secondary text-on-secondary cursor-wait'
                    : 'bg-primary text-on-primary hover:bg-primary/90'
                }`}
              >
                <span className="material-symbols-outlined text-sm">
                  {isInstalled ? 'check_circle' : isInstalling ? 'sync' : 'download'}
                </span>
                {isInstalled ? 'Installed' : isInstalling ? `Downloading (${installingModels[model.name]}%)` : 'Installer'}
              </button>
                );
              })()}
            </div>
          </div>
        ))}
      </div>

      {filteredModels?.length === 0 && (
        <div className="text-center py-20 bg-surface-container-lowest border border-dashed border-outline-variant/30">
          <span className="material-symbols-outlined text-outline-variant text-4xl mb-3">search_off</span>
          <p className="text-outline">No model matches your filters.</p>
        </div>
      )}
    </div>
  );
};

export default ModelHub;
