import { useState } from 'react';
import type { FC } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { configApi, evaluationApi } from '../services/api';

interface RegisteredModel {
  id?: string;
  name?: string;
  type?: string;
  active?: boolean;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** Appelé avec les evalIds créés, pour pré-sélectionner la comparaison. */
  onSubmitted: (evalIds: string[]) => void;
}

/**
 * Lance l'évaluation par lot de plusieurs modèles sur un même jeu de test
 * (POST /api/evaluation/batch), pour comparer équitablement leurs gains.
 */
const BatchEvaluateDialog: FC<Props> = ({ open, onClose, onSubmitted }) => {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<string[]>([]);
  const [testSetSize, setTestSetSize] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);

  const { data: models = [], isLoading } = useQuery({
    queryKey: ['config-models'],
    queryFn: async (): Promise<RegisteredModel[]> => {
      const data = (await configApi.getModels()).data ?? [];
      return (data as RegisteredModel[]).filter(m => (m.type ?? 'chat') === 'chat');
    },
    enabled: open,
  });

  if (!open) return null;

  const nameOf = (m: RegisteredModel) => m.name ?? m.id ?? '';
  const toggle = (name: string) =>
    setSelected(prev => (prev.includes(name) ? prev.filter(n => n !== name) : [...prev, name]));

  const handleSubmit = async () => {
    if (selected.length < 2) return;
    setSubmitting(true);
    try {
      const size = testSetSize.trim() ? parseInt(testSetSize, 10) : undefined;
      const res = await evaluationApi.submitBatch(selected, Number.isFinite(size) ? size : undefined);
      const evalIds: string[] = res.data?.evalIds ?? [];
      await queryClient.invalidateQueries({ queryKey: ['evaluation-reports'] });
      toast.success(`Batch evaluation started for ${selected.length} models`, {
        description: 'Models are evaluated sequentially on a shared test set. Track progress in the history.',
      });
      onSubmitted(evalIds);
      onClose();
      setSelected([]);
      setTestSetSize('');
    } catch (err: any) {
      toast.error('Failed to start batch evaluation', {
        description: err?.response?.data?.message ?? err?.message,
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Batch model evaluation"
      onClick={onClose}
    >
      <div
        className="bg-surface-container w-full max-w-md max-h-[80vh] flex flex-col border border-outline-variant/20"
        onClick={e => e.stopPropagation()}
      >
        <div className="px-5 py-4 border-b border-outline-variant/10">
          <p className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">Compare models</p>
          <h3 className="font-headline font-bold text-lg">Batch evaluation</h3>
          <p className="text-xs text-on-surface-variant mt-1">
            Selected models are evaluated one after another on the <strong>same test set</strong>,
            then auto-selected for comparison.
          </p>
        </div>

        <div className="px-5 py-3 overflow-y-auto flex-1">
          {isLoading ? (
            <p className="text-sm text-on-surface-variant py-6 text-center">Loading models…</p>
          ) : models.length === 0 ? (
            <p className="text-sm text-on-surface-variant py-6 text-center">
              No registered chat model found.
            </p>
          ) : (
            <ul className="space-y-1">
              {models.map(m => {
                const name = nameOf(m);
                return (
                  <li key={name}>
                    <label className="flex items-center gap-3 px-3 py-2 cursor-pointer hover:bg-surface-container-high/50">
                      <input
                        type="checkbox"
                        checked={selected.includes(name)}
                        onChange={() => toggle(name)}
                        className="accent-secondary"
                      />
                      <span className="font-headline text-sm truncate flex-1">{name}</span>
                      {m.active && (
                        <span className="font-label text-[8px] uppercase tracking-widest px-1.5 py-0.5 bg-primary/10 text-primary border border-primary/20">
                          active
                        </span>
                      )}
                    </label>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        <div className="px-5 py-4 border-t border-outline-variant/10 space-y-3">
          <label className="flex items-center justify-between gap-3">
            <span className="font-label text-[10px] uppercase tracking-widest text-on-surface-variant">
              Test set size (optional)
            </span>
            <input
              type="number"
              min={1}
              placeholder="auto"
              value={testSetSize}
              onChange={e => setTestSetSize(e.target.value)}
              className="w-24 bg-surface-container-low text-xs text-on-surface px-2 py-1 outline-none border border-outline-variant/20 focus:border-primary/50"
            />
          </label>
          <div className="flex items-center justify-end gap-3">
            <button
              onClick={onClose}
              className="px-3 py-2 text-[11px] font-label uppercase tracking-widest text-on-surface-variant hover:text-on-surface transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={handleSubmit}
              disabled={selected.length < 2 || submitting}
              className="px-4 py-2 bg-secondary text-on-secondary font-label text-[11px] uppercase tracking-widest
                         hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed transition-opacity"
              title={selected.length < 2 ? 'Select at least 2 models' : 'Start batch evaluation'}
            >
              {submitting ? 'Starting…' : `Evaluate ${selected.length} models`}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default BatchEvaluateDialog;
