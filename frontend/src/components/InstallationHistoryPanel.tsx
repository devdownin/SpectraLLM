import type { FC } from 'react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { modelsHubApi } from '../services/api';

/**
 * Historique persistant des installations Model Hub (llmfit download).
 *
 * Sans persistance, un redémarrage de l'API tuait le sous-processus llmfit et effaçait
 * tout le suivi. Les jobs étant désormais persistés en base et réconciliés au démarrage,
 * ce panneau rend l'historique visible : un téléchargement interrompu apparaît en FAILED
 * (« Interrompu par un redémarrage du serveur ») plutôt que figé à jamais.
 */

const STATUS_STYLE: Record<string, string> = {
  COMPLETED: 'border-primary text-primary',
  FAILED: 'border-error text-error',
  CANCELLED: 'border-outline-variant/40 text-outline',
  DOWNLOADING: 'border-secondary text-secondary',
  REGISTERING: 'border-secondary text-secondary',
  PENDING: 'border-outline-variant/40 text-outline',
};

const formatDate = (iso?: string) => {
  if (!iso) return '';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
};

const InstallationHistoryPanel: FC = () => {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);
  const queryClient = useQueryClient();

  // Relance d'un téléchargement échoué/annulé avec les MÊMES paramètres (modèle,
  // quantisation, auto-activation) : le job porte tout ce qu'il faut. Le serveur
  // répond 409 si un téléchargement de ce modèle est déjà en cours.
  const retryMutation = useMutation({
    mutationFn: (j: { modelName: string; quant?: string | null; autoActivate?: boolean }) =>
      modelsHubApi.installModel(j.modelName, j.quant ?? undefined, j.autoActivate ?? false),
    onSuccess: (_res, j) => {
      toast.success(t('installs.retryStarted', { name: j.modelName }));
      queryClient.invalidateQueries({ queryKey: ['models-installations'] });
    },
    onError: (error: any) => {
      toast.error(t('installs.retryFailed'), {
        description: error?.response?.data?.detail ?? error?.response?.data?.error ?? error?.message,
      });
    },
  });

  const { data: installations, isLoading } = useQuery({
    queryKey: ['models-installations'],
    queryFn: () => modelsHubApi.getInstallations().then(res => res.data),
    enabled: expanded,
    // Rafraîchit tant qu'un téléchargement est en cours pour refléter la progression.
    refetchInterval: (query) =>
      Array.isArray(query.state.data)
      && query.state.data.some((j: any) => j.status === 'DOWNLOADING' || j.status === 'PENDING' || j.status === 'REGISTERING')
        ? 3000 : false,
  });

  const jobs = Array.isArray(installations) ? installations : [];

  return (
    <section className="bg-surface-container-low border border-outline-variant/10">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left"
      >
        <div className="flex items-center gap-3">
          <span className="material-symbols-outlined text-outline">history</span>
          <span className="text-[11px] font-black uppercase tracking-widest text-primary font-headline">
            {t('installs.title')}
          </span>
          {jobs.length > 0 && (
            <span className="text-[11px] text-outline">{t('installs.count', { count: jobs.length })}</span>
          )}
        </div>
        <span className="material-symbols-outlined text-outline text-sm">
          {expanded ? 'expand_less' : 'expand_more'}
        </span>
      </button>

      {expanded && (
        <div className="border-t border-outline-variant/10 divide-y divide-outline-variant/10">
          {isLoading && <div className="px-4 py-3 text-xs text-outline">{t('installs.loading')}</div>}
          {!isLoading && jobs.length === 0 && (
            <div className="px-4 py-3 text-xs text-outline">{t('installs.empty')}</div>
          )}
          {jobs.map((j: any) => (
            <div key={j.jobId} className="px-4 py-2 flex items-center justify-between gap-4">
              <div className="min-w-0">
                <div className="text-xs font-mono truncate">
                  {j.modelName}{j.quant ? ` · ${j.quant}` : ''}
                </div>
                <div className="text-[11px] text-outline flex flex-wrap gap-2 items-center">
                  <span>{formatDate(j.createdAt)}</span>
                  {j.currentStep && <span className="italic">{j.currentStep}</span>}
                  {j.status === 'FAILED' && j.error && (
                    <span className="text-error truncate max-w-xs" title={j.error}>— {j.error}</span>
                  )}
                </div>
              </div>
              <div className="shrink-0 flex items-center gap-3">
                {(j.status === 'DOWNLOADING' || j.status === 'PENDING' || j.status === 'REGISTERING') && (
                  <span className="text-[11px] text-outline tabular-nums">{j.progress}%</span>
                )}
                {(j.status === 'FAILED' || j.status === 'CANCELLED') && (
                  <button
                    onClick={() => retryMutation.mutate(j)}
                    disabled={retryMutation.isPending}
                    title={t('installs.retryHint')}
                    className="flex items-center gap-1 px-2 py-1 border border-primary/40 text-primary text-[10px] font-black uppercase tracking-widest disabled:opacity-30 hover:bg-primary/10 transition-colors"
                  >
                    <span className={`material-symbols-outlined text-sm ${retryMutation.isPending ? 'animate-spin' : ''}`}>
                      {retryMutation.isPending ? 'sync' : 'replay'}
                    </span>
                    {t('installs.retry')}
                  </button>
                )}
                <span className={`px-1.5 py-0.5 border text-[11px] font-black uppercase tracking-widest ${STATUS_STYLE[j.status] ?? 'border-outline-variant/40 text-outline'}`}>
                  {j.status}
                </span>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
};

export default InstallationHistoryPanel;
