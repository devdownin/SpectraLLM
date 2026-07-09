import type { FC } from 'react';
import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { modelsHubApi } from '../services/api';
import { toast } from 'sonner';

const formatSize = (bytes: number) => {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${bytes} B`;
};

/**
 * Inventaire du volume des modèles : chaque GGUF avec sa taille, les alias du
 * registre qui le référencent et son statut actif. Ferme le cycle de vie
 * (télécharger → activer → retirer) — les GGUF pèsent plusieurs Go chacun.
 * La suppression est refusée côté serveur pour le modèle actif, et le fichier
 * n'est effacé que s'il n'est référencé par aucun autre modèle.
 */
const ModelStoragePanel: FC = () => {
  const [expanded, setExpanded] = useState(false);
  const queryClient = useQueryClient();

  const { data: storage, isLoading } = useQuery({
    queryKey: ['models-storage'],
    queryFn: () => modelsHubApi.getStorage().then(res => res.data),
    enabled: expanded,
  });

  const deleteMutation = useMutation({
    mutationFn: ({ name, type }: { name: string; type: string }) =>
      modelsHubApi.deleteModel(name, type, true),
    onSuccess: (res) => {
      const data = res.data;
      toast.success(`Model "${data.name}" removed from the registry`, {
        description: data.fileDeleted
          ? 'GGUF file deleted from data/models/.'
          : (data.fileSkippedReason ?? 'GGUF file kept.'),
      });
      queryClient.invalidateQueries({ queryKey: ['models-storage'] });
      queryClient.invalidateQueries({ queryKey: ['model-recommendations'] });
    },
    onError: (error: any) => {
      toast.error('Failed to delete model', {
        description: error?.response?.data?.detail ?? error?.response?.data?.error ?? error?.message,
      });
    },
  });

  return (
    <section className="bg-surface-container-low border border-outline-variant/10">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 text-left"
      >
        <div className="flex items-center gap-3">
          <span className="material-symbols-outlined text-outline">hard_drive</span>
          <span className="text-[11px] font-black uppercase tracking-widest text-primary font-headline">
            Storage — data/models/
          </span>
          {storage && (
            <span className="text-[11px] text-outline">
              {storage.files?.length ?? 0} GGUF · {formatSize(storage.totalBytes ?? 0)}
            </span>
          )}
        </div>
        <span className="material-symbols-outlined text-outline text-sm">
          {expanded ? 'expand_less' : 'expand_more'}
        </span>
      </button>

      {expanded && (
        <div className="border-t border-outline-variant/10 divide-y divide-outline-variant/10">
          {isLoading && <div className="px-4 py-3 text-xs text-outline">Loading…</div>}
          {!isLoading && (storage?.files ?? []).length === 0 && (
            <div className="px-4 py-3 text-xs text-outline">No GGUF file in the models volume.</div>
          )}
          {(storage?.files ?? []).map((f: any) => {
            const primaryRef = f.registeredAs?.[0];
            return (
              <div key={f.file} className="px-4 py-2 flex items-center justify-between gap-4">
                <div className="min-w-0">
                  <div className="text-xs font-mono truncate">{f.file}</div>
                  <div className="text-[11px] text-outline flex flex-wrap gap-2 items-center">
                    <span className="font-bold">{formatSize(f.sizeBytes)}</span>
                    {(f.registeredAs ?? []).map((r: any) => (
                      <span key={`${r.type}-${r.name}`} className={`px-1.5 py-0.5 border ${r.active ? 'border-primary text-primary font-bold' : 'border-outline-variant/30'}`}>
                        {r.name} · {r.type}{r.active ? ' · ACTIVE' : ''}
                      </span>
                    ))}
                    {(f.registeredAs ?? []).length === 0 && (
                      <span className="italic">not in the registry (orphan file)</span>
                    )}
                  </div>
                </div>
                {primaryRef && (
                  <button
                    onClick={() => deleteMutation.mutate({ name: primaryRef.name, type: primaryRef.type })}
                    disabled={f.active || deleteMutation.isPending}
                    title={f.active ? 'Active model — activate another one first' : 'Remove from registry and delete the GGUF'}
                    className="shrink-0 flex items-center gap-1 px-3 py-1.5 border border-error/40 text-error text-[11px] font-black uppercase tracking-widest disabled:opacity-30 hover:bg-error/10 transition-colors"
                  >
                    <span className="material-symbols-outlined text-sm">delete</span>
                    Delete
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </section>
  );
};

export default ModelStoragePanel;
