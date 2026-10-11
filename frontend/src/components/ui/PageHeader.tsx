import type { FC, ReactNode } from 'react';
import { cn } from './cn';
import { ActionMenu } from './ActionMenu';
import type { ActionMenuItem } from './ActionMenu';

export interface PageHeaderProps {
  /** Overline optionnelle au-dessus du titre (contexte, catégorie). */
  kicker?: ReactNode;
  title: ReactNode;
  description?: ReactNode;
  /** Actions principales de la page, alignées à droite. */
  actions?: ReactNode;
  primaryAction?: ReactNode;
  secondaryActions?: ActionMenuItem[];
  context?: ReactNode;
  className?: string;
}

/** En-tête standard de page : hiérarchie unique pour toutes les vues. */
export const PageHeader: FC<PageHeaderProps> = ({ kicker, title, description, actions, primaryAction, secondaryActions = [], context, className }) => (
  <header className={cn('flex flex-wrap items-start justify-between gap-4', className)}>
    <div className="min-w-0 flex-1 basis-64">
      {kicker && (
        <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-on-surface-variant mb-1">{kicker}</p>
      )}
      <h2 className="text-2xl font-semibold tracking-tight text-on-surface">{title}</h2>
      {description && <p className="text-[13px] text-on-surface-variant mt-1 max-w-2xl leading-relaxed">{description}</p>}
    </div>
    {(context || actions || primaryAction || secondaryActions.length > 0) && <div className="flex flex-wrap items-center justify-end gap-2 max-w-full">
      {context && <div className="text-xs text-on-surface-variant">{context}</div>}
      {actions}
      <ActionMenu items={secondaryActions} />
      {primaryAction}
    </div>}
  </header>
);

export default PageHeader;
