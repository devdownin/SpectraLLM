import type { FC } from 'react';
import { useLocation, Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { datasetApi, fineTuningApi } from '../services/api';
import Tooltip from './Tooltip';

/**
 * Fil de progression du pipeline (Ingestion → Dataset → Training → Querying),
 * câblé sur l'état RÉEL du système : une étape n'est cochée que si les données
 * correspondantes existent (chunks indexés, paires générées, job terminé) —
 * pas simplement parce que l'utilisateur navigue plus loin dans l'interface.
 */

interface WizardStep {
  path: string;
  label: string;
  icon: string;
  done: boolean;
  /** État affiché dans le tooltip (compte réel ou action attendue). */
  detail: string;
}

const WizardProgress: FC = () => {
  const location = useLocation();

  const { data: datasetStats } = useQuery({
    queryKey: ['wizard-dataset-stats'],
    queryFn: async () => (await datasetApi.getStats()).data as { chunksInStore?: number; totalPairs?: number },
    refetchInterval: 30_000,
    // Panne API déjà signalée par l'intercepteur axios : ici on affiche juste « non fait ».
    retry: false,
  });

  const { data: ftJobs } = useQuery({
    queryKey: ['wizard-ft-jobs'],
    queryFn: async () => (await fineTuningApi.getJobs()).data as { status?: string }[],
    refetchInterval: 30_000,
    retry: false,
  });

  const chunks = datasetStats?.chunksInStore ?? 0;
  const pairs = datasetStats?.totalPairs ?? 0;
  const completedJobs = Array.isArray(ftJobs)
    ? ftJobs.filter((j) => (j.status ?? '').toUpperCase() === 'COMPLETED').length
    : 0;

  const steps: WizardStep[] = [
    {
      path: '/ingestion',
      label: 'Ingestion',
      icon: 'cloud_upload',
      done: chunks > 0,
      detail: chunks > 0 ? `${chunks} chunks indexed` : 'Upload documents to build the knowledge base',
    },
    {
      path: '/ingestion',
      label: 'Dataset',
      icon: 'dataset',
      done: pairs > 0,
      detail: pairs > 0 ? `${pairs} training pairs generated` : 'Generate Q/A pairs from your documents',
    },
    {
      path: '/fine-tuning',
      label: 'Training',
      icon: 'model_training',
      done: completedJobs > 0,
      detail: completedJobs > 0
        ? `${completedJobs} fine-tuning job${completedJobs > 1 ? 's' : ''} completed`
        : 'Fine-tune a model on your dataset',
    },
    {
      path: '/playground',
      label: 'Querying',
      icon: 'chat_bubble',
      done: false,
      detail: 'Chat with your specialized model',
    },
  ];

  const onWizardPage = steps.some((s) => s.path === location.pathname);
  if (!onWizardPage && location.pathname !== '/') return null;

  // Étape « courante » : parmi celles de la page affichée, la première non
  // faite ; sinon la dernière (les deux premières partagent /ingestion).
  const matching = steps
    .map((s, i) => ({ ...s, index: i }))
    .filter((s) => s.path === location.pathname);
  const activeIndex = matching.length > 0
    ? (matching.find((s) => !s.done)?.index ?? matching[matching.length - 1].index)
    : -1;

  // Prochaine étape à réaliser (mise en évidence lorsque l'on est ailleurs, ex. Dashboard).
  const nextIndex = steps.findIndex((s) => !s.done);

  return (
    <div className="w-full bg-surface-container-low border-b border-outline-variant/10 px-8 py-3">
      <div className="max-w-4xl mx-auto flex items-center justify-between">
        {steps.map((step, index) => {
          const isActive = index === activeIndex;
          const isNext = !isActive && index === nextIndex;

          return (
            <div key={step.label} className="flex items-center flex-1 last:flex-none group">
              <Tooltip content={step.detail}>
                <Link
                  to={step.path}
                  aria-label={`${step.label} — ${step.detail}`}
                  className="flex flex-col items-center gap-1.5 transition-all outline-none"
                >
                  <div className={`
                    w-8 h-8 rounded-full flex items-center justify-center transition-all border
                    ${isActive ? 'border-primary bg-primary/10 text-primary scale-110 shadow-lg shadow-primary/10' :
                      step.done ? 'border-secondary bg-secondary/10 text-secondary' :
                      isNext ? 'border-primary/50 text-primary/80' :
                      'border-outline-variant/40 text-outline opacity-50'}
                  `}>
                    <span className="material-symbols-outlined text-[16px]">
                      {step.done ? 'check' : step.icon}
                    </span>
                  </div>
                  <span className={`
                    font-headline text-[10px] uppercase tracking-[0.15em] transition-colors
                    ${isActive ? 'text-primary font-bold' :
                      step.done ? 'text-secondary' :
                      isNext ? 'text-primary/80' :
                      'text-outline opacity-50'}
                  `}>
                    {step.label}
                  </span>
                </Link>
              </Tooltip>

              {index < steps.length - 1 && (
                <div className="flex-1 mx-4 h-[1px] bg-outline-variant/20 relative overflow-hidden">
                  {step.done && (
                    <div className="absolute inset-0 bg-secondary animate-in slide-in-from-left duration-1000"></div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default WizardProgress;
