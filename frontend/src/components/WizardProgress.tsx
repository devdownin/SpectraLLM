import type { FC } from 'react';
import { useLocation, Link } from 'react-router-dom';

const STEPS = [
  { path: '/datasets', label: 'Ingestion', icon: 'cloud_upload' },
  { path: '/pipelines', label: 'Dataset', icon: 'analytics' },
  { path: '/fine-tuning', label: 'Entraînement', icon: 'history' },
  { path: '/playground', label: 'Interrogation', icon: 'chat_bubble' },
];

const WizardProgress: FC = () => {
  const location = useLocation();
  
  const currentStepIndex = STEPS.findIndex(s => s.path === location.pathname);

  if (currentStepIndex === -1 && location.pathname !== '/') return null;

  return (
    <div className="w-full bg-surface-container-low border-b border-outline-variant/10 px-8 py-3">
      <div className="max-w-4xl mx-auto flex items-center justify-between">
        {STEPS.map((step, index) => {
          const isActive = location.pathname === step.path;
          const isDone = currentStepIndex > index;
          
          return (
            <div key={step.path} className="flex items-center flex-1 last:flex-none group">
              <Link 
                to={step.path}
                className="flex flex-col items-center gap-1.5 transition-all outline-none"
              >
                <div className={`
                  w-8 h-8 rounded-full flex items-center justify-center transition-all border
                  ${isActive ? 'border-primary bg-primary/10 text-primary scale-110 shadow-lg shadow-primary/10' : 
                    isDone ? 'border-secondary bg-secondary/10 text-secondary' : 
                    'border-outline-variant/40 text-outline opacity-50'}
                `}>
                  <span className="material-symbols-outlined text-[16px]">
                    {isDone ? 'check' : step.icon}
                  </span>
                </div>
                <span className={`
                  font-headline text-[8px] uppercase tracking-[0.15em] transition-colors
                  ${isActive ? 'text-primary font-bold' : isDone ? 'text-secondary' : 'text-outline opacity-50'}
                `}>
                  {step.label}
                </span>
              </Link>
              
              {index < STEPS.length - 1 && (
                <div className="flex-1 mx-4 h-[1px] bg-outline-variant/20 relative overflow-hidden">
                  {isDone && (
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
