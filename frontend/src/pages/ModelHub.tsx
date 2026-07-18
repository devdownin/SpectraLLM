import type { FC } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { Trans, useTranslation } from 'react-i18next';
import { modelsHubApi, configApi } from '../services/api';
import Skeleton from '../components/Skeleton';
import ModelStoragePanel from '../components/ModelStoragePanel';
import InstallationHistoryPanel from '../components/InstallationHistoryPanel';
import QualityBenchmarkCta from '../components/QualityBenchmarkCta';
import { EmptyState, Button, PageHeader, Field, Input } from '../components/ui';
import { useState, useEffect, useRef, useCallback } from 'react';
import { toast } from 'sonner';

const ModelHub: FC = () => {
  const { t } = useTranslation();
  const [installingModels, setInstallingModels] = useState<Record<string, number>>({});
  const [installedModels, setInstalledModels] = useState<string[]>([]);
  const [autoActivate, setAutoActivate] = useState(false);
  const activeEventSources = useRef<Map<string, EventSource>>(new Map());
  // autoActivate lu via ref pour ne pas recréer subscribeProgress (et donc l'effet de reprise).
  const autoActivateRef = useRef(autoActivate);
  autoActivateRef.current = autoActivate;
  // Installations lancées avec auto-activation : à leur fin, on propose le benchmark qualité
  // (candidate = modèle nouvellement actif, baseline = modèle qu'il remplace).
  const autoActivatedInstalls = useRef<Set<string>>(new Set());
  const [benchmarkCta, setBenchmarkCta] = useState<{ candidate: string; baseline: string } | null>(null);

  // Boucle « comparatif → qualité mesurée » : après une installation auto-activée, résout le
  // modèle actif (candidate) et le modèle précédent enregistré côté serveur (baseline) puis
  // propose de lancer le benchmark qualité sur le corpus. Best-effort (silencieux si indispo).
  const proposeBenchmark = useCallback(async (installedModelId: string) => {
    try {
      const [installsRes, cfgRes] = await Promise.all([
        modelsHubApi.getInstallations(),
        configApi.getModelConfig(),
      ]);
      const candidate: string | undefined = cfgRes.data?.model;
      const job = (installsRes.data as any[])
        .filter(j => j.modelName === installedModelId && j.status === 'COMPLETED' && j.previousActiveModel)
        .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())[0];
      const baseline: string | undefined = job?.previousActiveModel;
      if (candidate && baseline && candidate !== baseline) {
        setBenchmarkCta({ candidate, baseline });
      }
    } catch { /* best-effort : pas de CTA si la résolution échoue */ }
  }, []);

  const [filter, setFilter] = useState('All');
  const [limit, setLimit] = useState(12);
  const [simulation, setSimulation] = useState<{memory?: string, ram?: string, cpuCores?: number}>({});
  const [isSimulating, setIsSimulating] = useState(false);

  const { data: recommendations, isLoading, refetch, isFetching, isError, error: recommendationsError } = useQuery({
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
        toast.success(t('modelHub.downloaded', { name: modelName }), {
          description: autoActivateRef.current
            ? t('modelHub.downloadedActive')
            : t('modelHub.downloadedRegistered'),
          duration: 8000,
        });
        // Auto-activée : propose de mesurer la qualité vs le modèle remplacé (boucle comparatif→qualité).
        if (autoActivatedInstalls.current.has(modelName)) {
          autoActivatedInstalls.current.delete(modelName);
          proposeBenchmark(modelName);
        }
      }
    };
    eventSource.onerror = () => {
      cleanupSource();
      setInstallingModels(prev => { const next = { ...prev }; delete next[modelName]; persistInstalling(next); return next; });
      toast.error(t('modelHub.progressLost', { name: modelName }), {
        description: t('modelHub.progressLostDesc'),
      });
    };
  }, [refetch, proposeBenchmark, t]);

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
      // Mémorise l'auto-activation pour proposer le benchmark qualité à la fin du téléchargement.
      if (autoActivate) autoActivatedInstalls.current.add(variables.modelName);
      subscribeProgress(variables.modelName);
    },
    onError: (error: any) => {
      // L'API renvoie un ProblemDetail (RFC 9457) : le message utile est dans `detail`.
      toast.error(t('modelHub.installStartFailed'), {
        description: error?.response?.data?.detail ?? error?.response?.data?.message ?? error.message,
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
        <PageHeader title={t('nav.modelHub')} description={t('modelHub.kicker')} />
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
      <PageHeader
        title={t('nav.modelHub')}
        description={
          <Trans i18nKey="modelHub.subtitle">
            Discover the LLM models best suited to your current or simulated hardware configuration.
            Powered by <span className="text-primary font-semibold">llmfit</span>.
          </Trans>
        }
        actions={
        <div className="flex flex-wrap gap-3">
          <div className="flex items-center gap-2 bg-surface-container-low px-3 py-1 border border-outline-variant/20">
            <span className="text-[11px] font-black uppercase tracking-widest text-outline">{t('modelHub.show')}</span>
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
            <span className="text-[11px] font-black uppercase tracking-widest text-outline">{t('modelHub.filter')}</span>
            <select
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              className="bg-transparent text-xs font-bold text-primary focus:outline-none cursor-pointer"
            >
              <option value="All">{t('modelHub.filterAll')}</option>
              <option value="Perfect">{t('modelHub.filterPerfect')}</option>
              <option value="Good">{t('modelHub.filterGood')}</option>
              <option value="Marginal">{t('modelHub.filterMarginal')}</option>
              <option value="Runnable">{t('modelHub.filterRunnable')}</option>
            </select>
          </div>

          <label className="flex items-center gap-2 bg-surface-container-low px-3 py-1 border border-outline-variant/20 cursor-pointer">
            <input
              type="checkbox"
              checked={autoActivate}
              onChange={(e) => setAutoActivate(e.target.checked)}
              className="accent-primary"
            />
            <span className="text-[11px] font-black uppercase tracking-widest text-outline">{t('modelHub.autoActivate')}</span>
          </label>

          <Button
            variant={isSimulating ? 'primary' : 'secondary'}
            size="sm"
            icon="settings_input_component"
            onClick={() => setIsSimulating(!isSimulating)}
            aria-pressed={isSimulating}
          >
            {t('modelHub.simulation')}
          </Button>

          <Button variant="secondary" size="sm" onClick={() => refetch()}>
            <span aria-hidden="true" className={`material-symbols-outlined text-[16px] ${isFetching ? 'animate-spin' : ''}`}>refresh</span>
            {t('modelHub.refresh')}
          </Button>
        </div>
        }
      />

      {isSimulating && (
        <section className="bg-primary/5 p-6 rounded-xl border border-primary/20 animate-in slide-in-from-top-4 duration-300">
          <div className="flex items-center gap-2.5 mb-4">
             <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-primary">simulation</span>
             <h2 className="text-[14px] font-semibold text-on-surface">{t('modelHub.simulatorTitle')}</h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            <Field label={t('modelHub.simVram')}>
              {(field) => (
                <Input
                  {...field}
                  type="text"
                  placeholder={t('modelHub.simAuto')}
                  value={simulation.memory || ''}
                  onChange={(e) => setSimulation({...simulation, memory: e.target.value})}
                />
              )}
            </Field>
            <Field label={t('modelHub.simRam')}>
              {(field) => (
                <Input
                  {...field}
                  type="text"
                  placeholder={t('modelHub.simAuto')}
                  value={simulation.ram || ''}
                  onChange={(e) => setSimulation({...simulation, ram: e.target.value})}
                />
              )}
            </Field>
            <Field label={t('modelHub.simCpu')}>
              {(field) => (
                <Input
                  {...field}
                  type="number"
                  placeholder={t('modelHub.simAuto')}
                  value={simulation.cpuCores || ''}
                  onChange={(e) => setSimulation({...simulation, cpuCores: e.target.value ? parseInt(e.target.value) : undefined})}
                />
              )}
            </Field>
          </div>
          <div className="mt-4 flex justify-end gap-3">
            <Button variant="ghost" size="sm" onClick={() => { setSimulation({}); setIsSimulating(false); }}>
              {t('modelHub.simReset')}
            </Button>
          </div>
        </section>
      )}

      {recommendations?.system && (
        <section className="bg-surface-container-low p-4 border border-outline-variant/10 flex flex-wrap gap-8 items-center">
          <div className="flex items-center gap-3">
            <span className="material-symbols-outlined text-outline">cpu</span>
            <div>
              <div className="text-[11px] uppercase tracking-wider text-outline font-bold">{t('modelHub.sysCpu')}</div>
              <div className="text-sm font-medium">
                {recommendations.system.cpu_name} ({t('modelHub.sysCores', { count: recommendations.system.cpu_cores })})
              </div>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <span className="material-symbols-outlined text-outline">memory</span>
            <div>
              <div className="text-[11px] uppercase tracking-wider text-outline font-bold">{t('modelHub.sysRam')}</div>
              <div className="text-sm font-medium">
                {t('modelHub.sysRamValue', {
                  total: recommendations.system.total_ram_gb.toFixed(1),
                  available: recommendations.system.available_ram_gb.toFixed(1),
                })}
              </div>
            </div>
          </div>
          {(recommendations.system.has_gpu || simulation.memory) && (
            <div className="flex items-center gap-3">
              <span className="material-symbols-outlined text-secondary">memory_alt</span>
              <div>
                <div className="text-[11px] uppercase tracking-wider text-secondary font-bold">{t('modelHub.sysGpu')}</div>
                <div className="text-sm font-medium">{recommendations.system.gpu_name || t('modelHub.sysSimulated')} ({recommendations.system.gpu_vram_gb?.toFixed(1) || simulation.memory} VRAM)</div>
              </div>
            </div>
          )}
        </section>
      )}

      {/* Inventaire du volume des modèles (repliable) : tailles, alias, suppression */}
      <ModelStoragePanel />

      {/* Historique persistant des installations : survit au redémarrage de l'API,
          un téléchargement interrompu apparaît en FAILED plutôt que figé. */}
      <InstallationHistoryPanel />

      {/* Boucle comparatif → qualité : après une installation auto-activée, propose de mesurer
          la qualité réelle du nouveau modèle contre le précédent, sur le corpus. */}
      {benchmarkCta && (
        <QualityBenchmarkCta
          key={`${benchmarkCta.baseline}→${benchmarkCta.candidate}`}
          candidate={benchmarkCta.candidate}
          baseline={benchmarkCta.baseline}
          onDismiss={() => setBenchmarkCta(null)}
        />
      )}

      {/* Post-install info : le superviseur llm-chat recharge le modèle actif tout seul */}
      {installedModels.length > 0 && (
        <div className="bg-primary/5 border border-primary/30 p-4 flex items-start gap-3">
          <span className="material-symbols-outlined text-primary text-sm mt-0.5 shrink-0">info</span>
          <div className="space-y-1">
            <p className="text-[11px] font-label font-bold uppercase tracking-widest text-primary">
              {t('modelHub.postInstallTitle')}
            </p>
            <p className="text-[10px] text-on-surface-variant leading-relaxed">
              <Trans i18nKey="modelHub.postInstallBody">
                The GGUF file was copied to <code className="font-mono bg-surface-container px-1">data/models/</code> and
                saved to the model registry. Once <strong>activated</strong> (Auto-activation here, or from the
                Playground), <strong>llm-chat</strong> reloads it automatically within a few seconds — no manual
                restart needed.
              </Trans>
            </p>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-6">
        {filteredModels?.map((model: any) => (
          <div key={model.name} className="group relative bg-surface-container-lowest border border-outline-variant/10 hover:border-primary/30 transition-all flex flex-col h-full shadow-sm hover:shadow-md">
            <div className="p-6 flex-1">
              <div className="flex justify-between items-start mb-4">
                <div className="px-2 py-0.5 bg-primary/10 text-primary text-[11px] font-black uppercase tracking-widest border border-primary/20">
                  {model.parameter_count}
                </div>
                <div className="flex flex-col items-end">
                  <div className="text-2xl font-black text-primary font-headline leading-none">
                    {Math.round(model.score)}
                  </div>
                  <div className="text-[10px] uppercase tracking-tighter text-outline font-bold">{t('modelHub.fitScore')}</div>
                </div>
              </div>

              <h3 className="text-lg font-bold mb-1 group-hover:text-primary transition-colors break-words">{model.name}</h3>
              <p className="text-xs text-outline mb-4">{model.provider} • {model.use_case}</p>

              <div className="space-y-3 mt-6">
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">{t('modelHub.mode')}</span>
                  <span className={`font-bold uppercase tracking-wider ${model.run_mode === 'GPU' ? 'text-secondary' : 'text-primary'}`}>
                    {model.run_mode}
                  </span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">{t('modelHub.quant')}</span>
                  <span className="font-medium">{model.best_quant}</span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">{t('modelHub.speed')}</span>
                  <span className="font-medium">{model.estimated_tps?.toFixed(1)} tok/s</span>
                </div>
                <div className="flex justify-between items-center text-xs">
                  <span className="text-outline">{t('modelHub.memory')}</span>
                  <span className="font-medium">{model.memory_required_gb?.toFixed(1)} GB</span>
                </div>
              </div>

              <div className="mt-6 pt-6 border-t border-outline-variant/10">
                 <div className="text-[11px] font-black uppercase tracking-widest text-outline mb-2">{t('modelHub.capabilities')}</div>
                 <div className="flex flex-wrap gap-2">
                    {model.score_components && Object.entries(model.score_components).map(([key, val]: [string, any]) => (
                      <div key={key} className="flex flex-col">
                         <div className="h-1 w-12 bg-surface-variant rounded-full overflow-hidden">
                            <div className="h-full bg-primary" style={{ width: `${val}%` }}></div>
                         </div>
                         <span className="text-[10px] uppercase mt-1 text-outline">
                           {key === 'fit' ? t('modelHub.capFit') : key === 'quality' ? t('modelHub.capQuality') : key === 'speed' ? t('modelHub.capSpeed') : key}
                         </span>
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
                {isInstalled
                  ? t('modelHub.installed')
                  : isInstalling
                    ? t('modelHub.downloading', { pct: installingModels[model.name] })
                    : t('modelHub.install')}
              </button>
                );
              })()}
            </div>
          </div>
        ))}
      </div>

      {isError && (
        <div className="bg-surface-container rounded-xl ring-1 ring-error/25">
          <EmptyState
            icon="error"
            title={t('modelHub.loadError')}
            description={(recommendationsError as any)?.response?.data?.detail
              ?? t('modelHub.loadErrorHint')}
            action={
              <Button onClick={() => refetch()} icon="refresh">
                {t('modelHub.retry')}
              </Button>
            }
          />
        </div>
      )}

      {!isError && filteredModels?.length === 0 && (
        <div className="bg-surface-container rounded-xl ring-1 ring-white/[0.045]">
          <EmptyState
            icon="search_off"
            title={t('modelHub.emptyListTitle', 'No matching models')}
            description={
              <Trans i18nKey="modelHub.emptyList">
                No model matches your filters — or llmfit returned no recommendation (check
                <code className="font-mono bg-surface-container-high px-1 mx-1">docker compose logs spectra-api</code>
                if the list stays empty without filters).
              </Trans>
            }
          />
        </div>
      )}
    </div>
  );
};

export default ModelHub;
