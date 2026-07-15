import type { FC, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface ConfirmDialogProps {
  open: boolean;
  /** Titre court du dialogue. */
  title: string;
  /** Ce qui va se passer, en langage clair (peut inclure des compteurs). */
  message: ReactNode;
  /** Libellé du bouton de confirmation (défaut : Confirm/Confirmer). */
  confirmLabel?: string;
  /** Action destructive : bouton rouge (défaut true — usage principal : suppressions). */
  danger?: boolean;
  /** Désactive le bouton de confirmation (mutation en cours). */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/**
 * Dialogue de confirmation pour les actions irréversibles (suppressions…).
 *
 * Remplace les suppressions « en un clic » et les window.confirm natifs :
 * focus piégé, fermeture Échap, clic sur le fond = annuler, l'action
 * destructive n'est jamais le focus initial (le bouton Annuler l'est).
 */
const ConfirmDialog: FC<ConfirmDialogProps> = ({
  open, title, message, confirmLabel, danger = true, busy = false, onConfirm, onCancel,
}) => {
  const { t } = useTranslation();
  const ref = useFocusTrap<HTMLDivElement>(open, onCancel);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 backdrop-blur-sm p-4 animate-in fade-in duration-200"
      onClick={onCancel}
    >
      <div
        ref={ref}
        tabIndex={-1}
        role="alertdialog"
        aria-modal="true"
        aria-label={title}
        onClick={e => e.stopPropagation()}
        className="bg-surface-container border border-outline-variant/30 shadow-2xl w-full max-w-md outline-none animate-in zoom-in-95 duration-200"
      >
        <div className="px-6 py-5 space-y-3">
          <div className="flex items-start gap-3">
            <span
              aria-hidden="true"
              className={`material-symbols-outlined text-xl shrink-0 mt-0.5 ${danger ? 'text-error' : 'text-primary'}`}
            >
              {danger ? 'warning' : 'help'}
            </span>
            <div className="min-w-0 space-y-1.5">
              <h2 className="font-headline font-bold text-base">{title}</h2>
              <div className="text-sm text-on-surface-variant leading-relaxed">{message}</div>
            </div>
          </div>
        </div>
        <div className="px-6 py-4 border-t border-outline-variant/10 flex items-center justify-end gap-3">
          {/* Annuler en premier dans l'ordre de tabulation : le focus initial n'est jamais l'action destructive. */}
          <button
            type="button"
            onClick={onCancel}
            className="px-4 py-2 text-[11px] font-label uppercase tracking-widest text-on-surface-variant hover:text-on-surface border border-outline-variant/30 hover:border-outline-variant/60 transition-colors"
          >
            {t('confirm.cancel')}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className={`px-4 py-2 text-[11px] font-bold uppercase tracking-widest border transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
              danger
                ? 'border-error/40 text-error hover:bg-error hover:text-white'
                : 'border-primary/40 text-primary hover:bg-primary/10'
            }`}
          >
            {busy ? t('confirm.working') : (confirmLabel ?? t('confirm.confirm'))}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ConfirmDialog;
