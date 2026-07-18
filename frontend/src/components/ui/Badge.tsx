import type { FC, ReactNode } from 'react';
import { cn } from './cn';

export type BadgeTone = 'neutral' | 'primary' | 'secondary' | 'success' | 'warning' | 'error';

export interface BadgeProps {
  tone?: BadgeTone;
  /** Affiche une pastille d'état avant le libellé. */
  dot?: boolean;
  className?: string;
  children: ReactNode;
}

const TONES: Record<BadgeTone, string> = {
  neutral: 'text-on-surface-variant border-outline-variant bg-surface-container-high',
  primary: 'text-primary border-primary/30 bg-primary/10',
  secondary: 'text-secondary border-secondary/30 bg-secondary/10',
  success: 'text-success border-success/30 bg-success/10',
  warning: 'text-warning border-warning/30 bg-warning/10',
  error: 'text-error border-error/30 bg-error/10',
};

const DOTS: Record<BadgeTone, string> = {
  neutral: 'bg-outline',
  primary: 'bg-primary',
  secondary: 'bg-secondary',
  success: 'bg-success',
  warning: 'bg-warning',
  error: 'bg-error',
};

/** Étiquette d'état compacte (statuts, compteurs, tags). */
export const Badge: FC<BadgeProps> = ({ tone = 'neutral', dot = false, className, children }) => (
  <span
    className={cn(
      'inline-flex items-center gap-1.5 rounded-md border px-2 py-0.5 text-[11px] font-medium leading-5',
      TONES[tone],
      className,
    )}
  >
    {dot && <span aria-hidden="true" className={cn('w-1.5 h-1.5 rounded-full', DOTS[tone])} />}
    {children}
  </span>
);

export default Badge;
