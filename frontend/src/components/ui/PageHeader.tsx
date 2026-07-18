import type { FC, ReactNode } from 'react';
import { cn } from './cn';

export interface PageHeaderProps {
  /** Overline optionnelle au-dessus du titre (contexte, catégorie). */
  kicker?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  /** Actions principales de la page, alignées à droite. */
  actions?: ReactNode;
  className?: string;
}

/** En-tête standard de page : hiérarchie unique pour toutes les vues. */
export const PageHeader: FC<PageHeaderProps> = ({ kicker, title, description, actions, className }) => (
  <header className={cn('flex flex-wrap items-end justify-between gap-4', className)}>
    <div className="min-w-0">
      {kicker && (
        <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant mb-1">{kicker}</p>
      )}
      <h2 className="text-2xl font-semibold tracking-tight text-on-surface">{title}</h2>
      {description && <p className="text-[13px] text-on-surface-variant mt-1 max-w-2xl leading-relaxed">{description}</p>}
    </div>
    {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
  </header>
);

export default PageHeader;
