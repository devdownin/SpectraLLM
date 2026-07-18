import type { ButtonHTMLAttributes, FC, ReactNode } from 'react';
import { cn } from './cn';

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Nom d'icône Material Symbols affiché avant le libellé. */
  icon?: string;
  loading?: boolean;
  children?: ReactNode;
}

const VARIANTS: Record<ButtonVariant, string> = {
  primary:
    'bg-primary text-on-primary hover:bg-primary-fixed active:scale-[0.99] disabled:opacity-50',
  secondary:
    'bg-surface-container-high text-on-surface hover:bg-surface-container-highest border border-outline-variant disabled:opacity-50',
  outline:
    'bg-transparent text-on-surface border border-outline-variant hover:bg-surface-container-high disabled:opacity-50',
  ghost:
    'bg-transparent text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high disabled:opacity-50',
  danger:
    'bg-error-container text-on-error-container hover:brightness-110 disabled:opacity-50',
};

const SIZES: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-[12px] gap-1.5',
  md: 'h-9 px-4 text-[13px] gap-2',
  lg: 'h-10 px-5 text-[14px] gap-2',
};

/**
 * Bouton du design system. Toujours arrondi (8 px), transitions 150 ms,
 * focus clavier visible via le style global :focus-visible.
 */
export const Button: FC<ButtonProps> = ({
  variant = 'primary',
  size = 'md',
  icon,
  loading = false,
  className,
  children,
  disabled,
  ...props
}) => (
  <button
    className={cn(
      'inline-flex items-center justify-center rounded-lg font-medium transition-all duration-150 select-none disabled:cursor-not-allowed',
      VARIANTS[variant],
      SIZES[size],
      className,
    )}
    disabled={disabled || loading}
    {...props}
  >
    {loading ? (
      <span aria-hidden="true" className="material-symbols-outlined text-[16px] animate-spin">progress_activity</span>
    ) : (
      icon && <span aria-hidden="true" className="material-symbols-outlined text-[16px]">{icon}</span>
    )}
    {children}
  </button>
);

export default Button;
