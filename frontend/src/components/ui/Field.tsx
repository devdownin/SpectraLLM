import { forwardRef, useId } from 'react';
import type { InputHTMLAttributes, ReactNode, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react';
import { cn } from './cn';

/* ── Styles partagés des contrôles de saisie ── */
const CONTROL =
  'w-full rounded-lg bg-surface-container-low border text-[13px] text-on-surface ' +
  'placeholder:text-outline transition-colors focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed';
const CONTROL_STATE = (invalid?: boolean) =>
  invalid
    ? 'border-error/60 focus:border-error'
    : 'border-outline-variant focus:border-primary/60';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
}

/** Champ texte du design system (hauteur 36 px, focus discret). */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { invalid, className, ...props }, ref,
) {
  return (
    <input
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(CONTROL, CONTROL_STATE(invalid), 'h-9 px-3', className)}
      {...props}
    />
  );
});

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  invalid?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { invalid, className, ...props }, ref,
) {
  return (
    <textarea
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(CONTROL, CONTROL_STATE(invalid), 'px-3 py-2 min-h-[72px] resize-y', className)}
      {...props}
    />
  );
});

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  invalid?: boolean;
}

export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { invalid, className, children, ...props }, ref,
) {
  return (
    <select
      ref={ref}
      aria-invalid={invalid || undefined}
      className={cn(CONTROL, CONTROL_STATE(invalid), 'h-9 px-2.5 cursor-pointer', className)}
      {...props}
    >
      {children}
    </select>
  );
});

export interface FieldProps {
  label: ReactNode;
  /** Aide contextuelle affichée sous le contrôle. */
  description?: ReactNode;
  /** Message d'erreur : remplace la description et passe le contrôle en état invalide. */
  error?: ReactNode;
  /** Champ requis : astérisque accessible sur le label. */
  required?: boolean;
  className?: string;
  /** Un contrôle unique (Input, Select, Textarea…) recevant id/aria via render prop. */
  children: (props: { id: string; invalid: boolean; 'aria-describedby'?: string }) => ReactNode;
}

/**
 * Enveloppe de champ de formulaire : label cliquable, description ou erreur
 * inline, liaison aria automatique (label ↔ contrôle ↔ description).
 */
export function Field({ label, description, error, required, className, children }: FieldProps) {
  const id = useId();
  const descId = description || error ? `${id}-desc` : undefined;
  const invalid = Boolean(error);
  return (
    <div className={cn('space-y-1.5', className)}>
      <label htmlFor={id} className="block text-[12px] font-medium text-on-surface-variant">
        {label}
        {required && <span aria-hidden="true" className="text-error ml-0.5">*</span>}
      </label>
      {children({ id, invalid, 'aria-describedby': descId })}
      {error ? (
        <p id={descId} role="alert" className="text-[12px] text-error flex items-center gap-1">
          <span aria-hidden="true" className="material-symbols-outlined text-[13px]">error</span>
          {error}
        </p>
      ) : description ? (
        <p id={descId} className="text-[12px] text-outline leading-relaxed">{description}</p>
      ) : null}
    </div>
  );
}

export default Field;
