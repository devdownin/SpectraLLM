import type { FC } from 'react';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
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
  DOWNLOADING: 'border-secondary text-secondary',
  REGISTERING: 'border-secondary text-secondary',
  PENDING: 'border-outline-variant/40 text-outline',
};

const formatDate = (iso?: string) => {
  if (!iso) return '';
  try { return new Date(iso).toLocaleString(); } catch { return iso; }
};

const InstallationHistoryPanel: FC = () => {
  const [expanded, setExpanded] = useState(false);

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
            Installation history
          </span>
          {jobs.length > 0 && (
            <span className="text-[10px] text-outline">{jobs.length} download{jobs.length > 1 ? 's' : ''}</span>
          )}
        </div>
        <span className="material-symbols-outlined text-outline text-sm">
          {expanded ? 'expand_less' : 'expand_more'}
        </span>
      </button>

      {expanded && (
        <div className="border-t border-outline-variant/10 divide-y divide-outline-variant/10">
          {isLoading && <div className="px-4 py-3 text-xs text-outline">Loading…</div>}
          {!isLoading && jobs.length === 0 && (
            <div className="px-4 py-3 text-xs text-outline">No installation recorded yet.</div>
          )}
          {jobs.map((j: any) => (
            <div key={j.jobId} className="px-4 py-2 flex items-center justify-between gap-4">
              <div className="min-w-0">
                <div className="text-xs font-mono truncate">
                  {j.modelName}{j.quant ? ` · ${j.quant}` : ''}
                </div>
                <div className="text-[10px] text-outline flex flex-wrap gap-2 items-center">
                  <span>{formatDate(j.createdAt)}</span>
                  {j.currentStep && <span className="italic">{j.currentStep}</span>}
                  {j.status === 'FAILED' && j.error && (
                    <span className="text-error truncate max-w-xs" title={j.error}>— {j.error}</span>
                  )}
                </div>
              </div>
              <div className="shrink-0 flex items-center gap-3">
                {(j.status === 'DOWNLOADING' || j.status === 'PENDING' || j.status === 'REGISTERING') && (
                  <span className="text-[10px] text-outline tabular-nums">{j.progress}%</span>
                )}
                <span className={`px-1.5 py-0.5 border text-[10px] font-black uppercase tracking-widest ${STATUS_STYLE[j.status] ?? 'border-outline-variant/40 text-outline'}`}>
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
