import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from './Button';

export interface ActionMenuItem {
  id: string;
  label: string;
  icon?: string;
  onSelect: () => void;
  disabled?: boolean;
  pressed?: boolean;
  danger?: boolean;
}

/** Shared secondary actions: roving focus, Escape, Tab and outside dismissal. */
export function ActionMenu({ items, label, side = 'down' }: { items: ActionMenuItem[]; label?: string; side?: 'up' | 'down' }) {
  const { t } = useTranslation();
  const id = useId();
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const initialFocus = useRef<'first' | 'last'>('first');
  const enabledItems = () => Array.from(container.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled), [role="menuitemcheckbox"]:not(:disabled)') ?? []);
  useEffect(() => {
    if (!open) return;
    const buttons = enabledItems();
    (initialFocus.current === 'last' ? buttons.at(-1) : buttons[0])?.focus();
    const outside = (event: PointerEvent) => { if (!container.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);
  if (!items.length) return null;
  return <div ref={container} className="relative">
    <Button ref={trigger} variant="outline" icon="more_horiz" aria-haspopup="menu" aria-controls={id} aria-expanded={open}
      onClick={() => { initialFocus.current = 'first'; setOpen(value => !value); }}
      onKeyDown={event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault(); initialFocus.current = event.key === 'ArrowUp' ? 'last' : 'first'; setOpen(true);
        }
      }}>{label ?? t('ui.moreActions')}</Button>
    {open && <div id={id} role="menu" aria-label={label ?? t('ui.moreActions')} className={`absolute right-0 ${side === 'up' ? 'bottom-full mb-2' : 'top-full mt-2'} z-40 w-64 max-w-[calc(100vw-2rem)] rounded-xl border border-outline-variant/40 bg-surface-container-high shadow-xl p-1`}
      onKeyDown={event => {
        const buttons = enabledItems(); const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
        if (event.key === 'Escape') { event.preventDefault(); setOpen(false); trigger.current?.focus(); }
        else if (event.key === 'Tab') { setOpen(false); trigger.current?.focus(); }
        else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
          event.preventDefault();
          const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length;
          buttons[next]?.focus();
        }
      }}>
      {items.map(item => <button key={item.id} type="button" role={item.pressed === undefined ? 'menuitem' : 'menuitemcheckbox'} aria-checked={item.pressed} disabled={item.disabled}
        className={`w-full flex items-center gap-2 rounded-lg px-3 py-2 text-left text-sm disabled:opacity-40 hover:bg-surface-container-highest ${item.danger ? 'text-error' : 'text-on-surface'}`}
        onClick={() => { setOpen(false); trigger.current?.focus(); item.onSelect(); }}>
        {item.icon && <span aria-hidden="true" className="material-symbols-outlined text-base">{item.icon}</span>}
        <span className="flex-1">{item.label}</span>{item.pressed && <span aria-hidden="true">✓</span>}
      </button>)}
    </div>}
  </div>;
}
