import { useState } from 'react';
import type { FC } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { configApi, evaluationApi } from '../services/api';
import { useFocusTrap } from '../hooks/useFocusTrap';

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
  const { t } = useTranslation();
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

  // Piège de focus + fermeture Échap — même comportement que la fiche Documents.
  const dialogRef = useFocusTrap<HTMLDivElement>(open, onClose);

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
      toast.success(t('batchEval.started', { count: selected.length }), {
        description: t('batchEval.startedDesc'),
      });
      onSubmitted(evalIds);
      onClose();
      setSelected([]);
      setTestSetSize('');
    } catch (err: any) {
      toast.error(t('batchEval.startFailed'), {
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
      aria-label={t('batchEval.aria')}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        tabIndex={-1}
        className="bg-surface-container w-full max-w-md max-h-[80vh] flex flex-col border border-outline-variant/20 outline-none"
        onClick={e => e.stopPropagation()}
      >
        <div className="px-5 py-4 border-b border-outline-variant/10">
          <p className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">{t('batchEval.kicker')}</p>
          <h3 className="font-headline font-bold text-lg">{t('batchEval.title')}</h3>
          <p className="text-xs text-on-surface-variant mt-1">
            <Trans i18nKey="batchEval.description">
              Selected models are evaluated one after another on the <strong>same test set</strong>,
              then auto-selected for comparison.
            </Trans>
          </p>
        </div>

        <div className="px-5 py-3 overflow-y-auto flex-1">
          {isLoading ? (
            <p className="text-sm text-on-surface-variant py-6 text-center">{t('batchEval.loading')}</p>
          ) : models.length === 0 ? (
            <p className="text-sm text-on-surface-variant py-6 text-center">
              {t('batchEval.noModels')}
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
                        <span className="font-label text-[10px] uppercase tracking-widest px-1.5 py-0.5 bg-primary/10 text-primary border border-primary/20">
                          {t('batchEval.active')}
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
            <span className="font-label text-[11px] uppercase tracking-widest text-on-surface-variant">
              {t('batchEval.testSetSize')}
            </span>
            <input
              type="number"
              min={1}
              placeholder={t('batchEval.auto')}
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
              {t('batchEval.cancel')}
            </button>
            <button
              onClick={handleSubmit}
              disabled={selected.length < 2 || submitting}
              className="px-4 py-2 bg-secondary text-on-secondary font-label text-[11px] uppercase tracking-widest
                         hover:opacity-90 disabled:opacity-40 disabled:cursor-not-allowed transition-opacity"
              title={selected.length < 2 ? t('batchEval.needTwo') : t('batchEval.submitHint')}
            >
              {submitting ? t('batchEval.starting') : t('batchEval.submit', { count: selected.length })}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default BatchEvaluateDialog;
