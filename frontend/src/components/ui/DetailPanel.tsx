import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { useFocusTrap } from '../../hooks/useFocusTrap';
import ContextBackLink from '../ContextBackLink';

type Section = 'summary' | 'details' | 'history' | 'actions';
const sections: Section[] = ['summary', 'details', 'history', 'actions'];

/** One scroll surface and the same keyboard/close contract for every detail view. */
export function DetailPanel({ title, onClose, summary, details, history, actions, closeLabel }: {
  title: ReactNode; onClose: () => void; summary: ReactNode; details: ReactNode;
  history: ReactNode; actions: ReactNode; closeLabel?: string;
}) {
  const { t } = useTranslation();
  const id = useId();
  const ref = useFocusTrap<HTMLDivElement>(true, onClose);
  const nav = useRef<HTMLElement>(null);
  useEffect(() => {
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, []);
  const content = { summary, details, history, actions };
  return createPortal(<div className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex justify-end" onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} className="w-full max-w-6xl h-full bg-surface-container-high border-l border-outline-variant/30 shadow-2xl flex flex-col min-w-0 outline-none">
      <header className="p-4 md:p-6 border-b border-outline-variant/30 flex items-center justify-between gap-3 shrink-0">
        <div className="min-w-0"><ContextBackLink /><h2 id={`${id}-title`} className="text-lg font-semibold break-words">{title}</h2></div>
        <button type="button" onClick={onClose} aria-label={closeLabel ?? t('detail.close')} className="rounded-lg w-11 h-11 shrink-0 hover:bg-surface-container-highest"><span aria-hidden="true" className="material-symbols-outlined">close</span></button>
      </header>
      <nav ref={nav} aria-label={t('detail.sections')} className="flex flex-wrap gap-2 px-4 md:px-6 py-3 border-b border-outline-variant/30 shrink-0" onKeyDown={event => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        const buttons = Array.from(nav.current?.querySelectorAll('button') ?? []);
        const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length;
        event.preventDefault(); buttons[next]?.focus();
      }}>
        {sections.map(section => <button key={section} type="button" aria-controls={`${id}-${section}`} className="text-sm px-3 py-2 rounded-lg border border-outline-variant/40 hover:bg-surface-container-highest" onClick={() => {
          const target = document.getElementById(`${id}-${section}`);
          target?.focus({ preventScroll: true }); target?.scrollIntoView({ block: 'start', behavior: 'smooth' });
        }}>{t(`detail.${section}`)}</button>)}
      </nav>
      <div className="flex-1 min-h-0 overflow-y-auto overscroll-contain p-4 md:p-6 space-y-8 custom-scrollbar">
        {sections.map(section => <section key={section} id={`${id}-${section}`} tabIndex={-1} aria-labelledby={`${id}-${section}-heading`} className="scroll-mt-4 min-w-0 space-y-3 outline-none focus:ring-2 focus:ring-primary/40 rounded-lg">
          <h3 id={`${id}-${section}-heading`} className="text-xs font-semibold uppercase tracking-wide text-on-surface-variant">{t(`detail.${section}`)}</h3>
          {content[section] ?? <p className="text-sm text-on-surface-variant">{t('detail.unavailable')}</p>}
        </section>)}
      </div>
    </div>
  </div>, document.body);
}
