import type { FC, HTMLAttributes, ReactNode } from 'react';
import { cn } from './cn';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** Carte cliquable/survolable : élévation discrète au hover. */
  interactive?: boolean;
  padding?: 'none' | 'sm' | 'md' | 'lg';
  children?: ReactNode;
}

const PADDINGS = { none: '', sm: 'p-4', md: 'p-5', lg: 'p-6' } as const;

/** Panneau de contenu : surface + liseré 1 px + rayon 12 px. */
export const Card: FC<CardProps> = ({ interactive = false, padding = 'md', className, children, ...props }) => (
  <div
    className={cn(
      'bg-surface-container rounded-xl ring-1 ring-white/[0.045]',
      interactive && 'card-hover cursor-pointer',
      PADDINGS[padding],
      className,
    )}
    {...props}
  >
    {children}
  </div>
);

export interface CardHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  /** Actions alignées à droite (boutons, liens). */
  actions?: ReactNode;
  className?: string;
}

export const CardHeader: FC<CardHeaderProps> = ({ title, description, actions, className }) => (
  <div className={cn('flex items-start justify-between gap-3 mb-4', className)}>
    <div className="min-w-0">
      <h3 className="text-[14px] font-semibold text-on-surface tracking-tight">{title}</h3>
      {description && <p className="text-[12px] text-on-surface-variant mt-0.5 leading-relaxed">{description}</p>}
    </div>
    {actions && <div className="flex items-center gap-2 shrink-0">{actions}</div>}
  </div>
);

export default Card;
