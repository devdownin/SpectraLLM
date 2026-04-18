import type { FC } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { modelsHubApi } from '../services/api';
import Skeleton from '../components/Skeleton';
import { useState } from 'react';

const ModelHub: FC = () => {
  const [installingModels, setInstallingModels] = useState<Record<string, number>>({});
  const [autoActivate, setAutoActivate] = useState(false);
  const [filter, setFilter] = useState('All');
  const [limit, setLimit] = useState(12);
  const [simulation, setSimulation] = useState<{memory?: string, ram?: string, cpuCores?: number}>({});
  const [isSimulating, setIsSimulating] = useState(false);

  const { data: recommendations, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['model-recommendations', simulation, limit],
    queryFn: () => modelsHubApi.getRecommendations({ limit, ...simulation }).then(res => res.data),
  });

  const installMutation = useMutation({
    mutationFn: ({ modelName, quant }: { modelName: string; quant?: string }) =>
      modelsHubApi.installModel(modelName, quant, autoActivate),
    onSuccess: (_, variables) => {
      setInstallingModels(prev => ({ ...prev, [variables.modelName]: 0 }));

      const eventSource = modelsHubApi.getProgressSource(variables.modelName);
      eventSource.onmessage = (event) => {
        const progress = parseInt(event.data);
        setInstallingModels(prev => ({ ...prev, [variables.modelName]: progress }));
        if (progress >= 100) {
          eventSource.close();
        }
      };
      eventSource.onerror = () => {
        eventSource.close();
      };
    },
    onError: (error: any) => {
      alert(`Erreur lors du lancement de l'installation : ${error.message}`);
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
          <p className="text-outline mt-2">Optimisation matérielle par llmfit</p>
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
            Découvrez les modèles LLM les mieux adaptés à votre configuration matérielle actuelle ou simulée.
            Propulsé par <span className="text-primary font-bold">llmfit</span>.
          </p>
        </div>

        <div className="flex flex-wrap gap-3">
          <div className="flex items-center gap-2 bg-surface-container-low px-3 py-1 border border-outline-variant/20">
            <span className="text-[10px] font-black uppercase tracking-widest text-outline">Afficher</span>
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
            <span className="text-[10px] font-black uppercase tracking-widest text-outline">Filtre</span>
            <select
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="bg-transparent text-xs font-bold text-primary focus:outline-none cursor-pointer"
            >
              <option value="All">Tous</option>
              <option value="Perfect">Perfect</option>
              <option value="Good">Good</option>
              <option value="Marginal">Marginal</option>
              <option value="Runnable">Exécutables</option>
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
            <span className="font-headline uppercase tracking-widest text-[10px] font-bold">Actualiser</span>
          </button>
        </div>
      </header>

      {isSimulating && (
        <section className="bg-primary/5 p-6 border border-primary/20 animate-in slide-in-from-top-4 duration-300">
          <div className="flex items-center gap-3 mb-4">
             <span className="material-symbols-outlined text-primary">simulation</span>
             <h2 className="text-sm font-black uppercase tracking-widest text-primary font-headline">Simulateur de Matériel</h2>
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
              <label className="text-[10px] uppercase tracking-wider text-outline font-bold">RAM Système (ex: 32G, 64G)</label>
              <input
                type="text"
                placeholder="Auto"
                value={simulation.ram || ''}
                onChange={(e) => setSimulation({...simulation, ram: e.target.value})}
                className="w-full bg-surface-container-lowest border border-outline-variant/20 p-2 text-sm focus:border-primary outline-none"
              />
            </div>
            <div className="space-y-2">
              <label className="text-[10px] uppercase tracking-wider text-outline font-bold">Cœurs CPU</label>
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
                <div className="text-sm font-medium">{recommendations.system.gpu_name || 'Simulé'} ({recommendations.system.gpu_vram_gb?.toFixed(1) || simulation.memory} VRAM)</div>
              </div>
            </div>
          )}
        </section>
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
                  <span className="text-outline">Vitesse estimée</span>
                  <span className="font-medium">{model.estimated_tps?.toFixed(1)} tok/s</span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">Mémoire requise</span>
                  <span className="font-medium">{model.memory_required_gb?.toFixed(1)} GB</span>
                </div>
              </div>

              <div className="mt-6 pt-6 border-t border-outline-variant/10">
                 <div className="text-[10px] font-black uppercase tracking-widest text-outline mb-2">Capacités</div>
                 <div className="flex flex-wrap gap-2">
                    {model.score_components && Object.entries(model.score_components).map(([key, val]: [string, any]) => (
                      <div key={key} className="flex flex-col">
                         <div className="h-1 w-12 bg-surface-variant rounded-full overflow-hidden">
                            <div className="h-full bg-primary" style={{ width: `${val}%` }}></div>
                         </div>
                         <span className="text-[8px] uppercase mt-1 text-outline-variant">{key === 'fit' ? 'fit' : key === 'quality' ? 'qualité' : key === 'speed' ? 'vitesse' : key}</span>
                      </div>
                    ))}
                 </div>
              </div>
            </div>

            <div className="relative">
              {installingModels[model.name] !== undefined && installingModels[model.name] < 100 && (
                <div className="absolute top-0 left-0 h-1 bg-secondary transition-all duration-300" style={{ width: `${installingModels[model.name]}%` }}></div>
              )}
              <button
                onClick={() => handleInstall(model.name, model.best_quant)}
                disabled={model.installed || installingModels[model.name] !== undefined}
                className={`w-full py-4 font-headline uppercase tracking-[0.2em] text-[11px] font-black flex items-center justify-center gap-2 transition-all ${
                  model.installed
                  ? 'bg-surface-variant text-outline cursor-default'
                  : installingModels[model.name] !== undefined
                    ? 'bg-secondary text-on-secondary cursor-wait'
                    : 'bg-primary text-on-primary hover:bg-primary/90'
                }`}
              >
                <span className="material-symbols-outlined text-sm">
                  {model.installed ? 'check_circle' : installingModels[model.name] !== undefined ? 'sync' : 'download'}
                </span>
                {model.installed ? 'Installé' : installingModels[model.name] !== undefined ? `Téléchargement (${installingModels[model.name]}%)` : 'Installer'}
              </button>
            </div>
          </div>
        ))}
      </div>

      {filteredModels?.length === 0 && (
        <div className="text-center py-20 bg-surface-container-lowest border border-dashed border-outline-variant/30">
          <span className="material-symbols-outlined text-outline-variant text-4xl mb-3">search_off</span>
          <p className="text-outline">Aucun modèle ne correspond à vos filtres.</p>
        </div>
      )}
    </div>
  );
};

export default ModelHub;
