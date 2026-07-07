import type { FC } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { configApi } from '../services/api';
import { toast } from 'sonner';

/**
 * Cohérence embedding ↔ index vectoriel.
 *
 * N'apparaît que lorsqu'il y a quelque chose à dire : au moins une collection
 * MISMATCH (indexée avec un autre modèle que le modèle d'embedding actif — RAG
 * bloqué dessus) ou une ré-indexation en cours/échouée. Le bouton « Reindex »
 * recalcule les vecteurs en place (textes conservés, pas de ré-ingestion).
 */
const EmbeddingConsistencyCard: FC = () => {
  const queryClient = useQueryClient();

  const { data: consistency } = useQuery({
    queryKey: ['embedding-consistency'],
    queryFn: () => configApi.getEmbeddingConsistency().then(res => res.data),
    refetchInterval: 60_000,
  });

  const { data: reindexStatuses } = useQuery({
    queryKey: ['embedding-reindex'],
    queryFn: () => configApi.getReindexStatuses().then(res => res.data),
    // Poll rapproché uniquement quand une ré-indexation tourne.
    refetchInterval: (query) =>
      (query.state.data as any[])?.some((s) => s.status === 'RUNNING') ? 2_000 : 30_000,
  });

  const reindexMutation = useMutation({
    mutationFn: (collection: string) => configApi.reindexCollection(collection),
    onSuccess: (_, collection) => {
      toast.info(`Reindexing "${collection}"`, {
        description: 'Vectors are being recomputed with the active embedding model. The collection stays blocked for RAG until completion.',
      });
      queryClient.invalidateQueries({ queryKey: ['embedding-reindex'] });
    },
    onError: (error: any) => {
      toast.error('Failed to start reindexing', {
        description: error?.response?.data?.error ?? error?.response?.data?.detail ?? error?.message,
      });
    },
  });

  const mismatches = (consistency?.collections ?? []).filter((c: any) => c.status === 'MISMATCH');
  const activeStatuses: any[] = reindexStatuses ?? [];
  const runningOrFailed = activeStatuses.filter((s) => s.status === 'RUNNING' || s.status === 'FAILED');

  if (mismatches.length === 0 && runningOrFailed.length === 0) return null;

  const statusFor = (collection: string) => activeStatuses.find((s) => s.collection === collection);

  return (
    <section className="bg-error/5 border border-error/30 p-5 space-y-4">
      <div className="flex items-center gap-3">
        <span className="material-symbols-outlined text-error">sync_problem</span>
        <div>
          <h2 className="text-sm font-black uppercase tracking-widest text-error font-headline">
            Embedding / index mismatch
          </h2>
          <p className="text-[10px] text-outline">
            Active embedding model: <span className="font-mono text-primary">{consistency?.activeEmbeddingModel}</span>
            {' '}— RAG is blocked on mismatched collections until they are reindexed.
          </p>
        </div>
      </div>

      <div className="space-y-2">
        {mismatches.map((c: any) => {
          const status = statusFor(c.name);
          const running = status?.status === 'RUNNING';
          const progress = running && status.total > 0 ? Math.round((status.processed / status.total) * 100) : null;
          return (
            <div key={c.name} className="flex items-center justify-between gap-4 bg-surface-container-lowest border border-outline-variant/10 px-4 py-2">
              <div className="min-w-0">
                <div className="text-sm font-bold truncate">{c.name}</div>
                <div className="text-[10px] text-outline">
                  indexed with <span className="font-mono">{c.indexedWith}</span>
                  {status?.status === 'FAILED' && (
                    <span className="text-error ml-2">last reindex failed: {status.error}</span>
                  )}
                </div>
              </div>
              {running ? (
                <div className="flex items-center gap-2 shrink-0">
                  <span className="material-symbols-outlined text-secondary text-sm animate-spin">sync</span>
                  <span className="text-xs font-bold text-secondary">
                    {progress !== null ? `${progress}%` : '…'} ({status.processed}/{status.total || '?'})
                  </span>
                </div>
              ) : (
                <button
                  onClick={() => reindexMutation.mutate(c.name)}
                  disabled={activeStatuses.some((s) => s.status === 'RUNNING')}
                  className="shrink-0 px-3 py-1.5 bg-error text-on-error font-headline uppercase tracking-widest text-[10px] font-black disabled:opacity-40"
                >
                  Reindex
                </button>
              )}
            </div>
          );
        })}

        {/* Ré-indexations en cours/échouées sur des collections déjà cohérentes (rare) */}
        {runningOrFailed.filter((s) => !mismatches.some((c: any) => c.name === s.collection)).map((s) => (
          <div key={s.collection} className="flex items-center justify-between gap-4 bg-surface-container-lowest border border-outline-variant/10 px-4 py-2">
            <div className="text-sm font-bold truncate">{s.collection}</div>
            <div className="text-xs text-outline">
              {s.status === 'RUNNING' ? `reindexing… ${s.processed}/${s.total || '?'}` : `failed: ${s.error}`}
            </div>
          </div>
        ))}
      </div>
    </section>
  );
};

export default EmbeddingConsistencyCard;
