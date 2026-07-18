import type { FC, ReactNode } from 'react';
import { cn } from './cn';

export interface EmptyStateProps {
  /** Nom d'icône Material Symbols. */
  icon?: string;
  title: ReactNode;
  description?: ReactNode;
  /** Action principale (typiquement un <Button>). */
  action?: ReactNode;
  className?: string;
}

/** État vide : guide l'utilisateur vers la prochaine action utile. */
export const EmptyState: FC<EmptyStateProps> = ({ icon = 'inbox', title, description, action, className }) => (
  <div className={cn('flex flex-col items-center justify-center text-center py-12 px-6', className)}>
    <div className="w-11 h-11 rounded-xl bg-surface-container-high flex items-center justify-center mb-4">
      <span aria-hidden="true" className="material-symbols-outlined text-[22px] text-on-surface-variant">{icon}</span>
    </div>
    <h3 className="text-[14px] font-semibold text-on-surface">{title}</h3>
    {description && (
      <p className="text-[12px] text-on-surface-variant mt-1 max-w-sm leading-relaxed">{description}</p>
    )}
    {action && <div className="mt-4">{action}</div>}
  </div>
);

export default EmptyState;
