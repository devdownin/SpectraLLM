import { useEffect, useMemo, useRef, useState } from 'react';
import type { FC } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { NAV_ITEMS, DOCUMENTATION_ITEM } from '../navigation';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface Command {
  id: string;
  /** Groupe d'affichage : pages ou actions. */
  section: 'pages' | 'actions';
  label: string;
  /** Termes additionnels pour la recherche (route, synonymes). */
  keywords: string;
  icon: string;
  run: () => void;
}

interface CommandPaletteProps {
  open: boolean;
  onClose: () => void;
}

/** Normalise pour une recherche insensible à la casse et aux accents. */
function fold(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/**
 * Palette de commandes globale (⌘K / Ctrl+K) : navigation vers toutes les
 * pages et actions rapides, au clavier. Filtre insensible aux accents,
 * navigation ↑/↓, Entrée pour exécuter, Échap pour fermer.
 */
const CommandPalette: FC<CommandPaletteProps> = ({ open, onClose }) => {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const trapRef = useFocusTrap<HTMLDivElement>(open, onClose);

  const commands = useMemo<Command[]>(() => {
    const go = (path: string) => () => { navigate(path); onClose(); };
    const pages: Command[] = [...NAV_ITEMS, DOCUMENTATION_ITEM].map((item) => ({
      id: `page:${item.path}`,
      section: 'pages',
      label: t(item.nameKey, item.name),
      keywords: `${item.name} ${item.path}`,
      icon: item.icon,
      run: go(item.path),
    }));
    const actions: Command[] = [
      {
        id: 'action:new-model',
        section: 'actions',
        label: t('nav.newModel'),
        keywords: 'new model fine-tuning train nouveau modele entrainement',
        icon: 'add',
        run: go('/fine-tuning'),
      },
      {
        id: 'action:upload',
        section: 'actions',
        label: t('palette.uploadDocs', 'Upload documents'),
        keywords: 'upload ingest documents televerser ingestion',
        icon: 'cloud_upload',
        run: go('/ingestion'),
      },
      {
        id: 'action:new-eval',
        section: 'actions',
        label: t('palette.newEvaluation', 'New evaluation'),
        keywords: 'evaluation benchmark score comparison nouvelle evaluation',
        icon: 'analytics',
        run: go('/comparison'),
      },
      {
        id: 'action:language',
        section: 'actions',
        label: t('palette.switchLanguage', 'Switch language (FR/EN)'),
        keywords: 'language langue french english francais anglais',
        icon: 'language',
        run: () => {
          i18n.changeLanguage(i18n.resolvedLanguage === 'fr' ? 'en' : 'fr');
          onClose();
        },
      },
    ];
    return [...pages, ...actions];
  }, [navigate, onClose, t, i18n]);

  const filtered = useMemo(() => {
    const q = fold(query.trim());
    if (!q) return commands;
    return commands.filter((c) => fold(`${c.label} ${c.keywords}`).includes(q));
  }, [commands, query]);

  // Réinitialise la saisie et la sélection à chaque ouverture, puis focus champ.
  useEffect(() => {
    if (open) {
      setQuery('');
      setActiveIndex(0);
      // Après le focus trap (qui focalise le conteneur), cible le champ.
      requestAnimationFrame(() => inputRef.current?.focus());
    }
  }, [open]);

  // Borne l'index actif quand le filtre change.
  useEffect(() => {
    setActiveIndex((i) => Math.min(i, Math.max(0, filtered.length - 1)));
  }, [filtered.length]);

  // Garde l'élément actif visible dans la liste scrollable.
  useEffect(() => {
    const el = listRef.current?.querySelector(`[data-index="${activeIndex}"]`);
    el?.scrollIntoView?.({ block: 'nearest' });
  }, [activeIndex]);

  if (!open) return null;

  const onKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActiveIndex((i) => (i + 1) % Math.max(1, filtered.length));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((i) => (i - 1 + Math.max(1, filtered.length)) % Math.max(1, filtered.length));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      filtered[activeIndex]?.run();
    }
  };

  const sections: Array<{ key: Command['section']; title: string }> = [
    { key: 'pages', title: t('palette.pages', 'Pages') },
    { key: 'actions', title: t('palette.actions', 'Actions') },
  ];

  return (
    <div
      className="fixed inset-0 z-[70] flex items-start justify-center bg-black/60 backdrop-blur-sm p-4 pt-[12vh] animate-in fade-in duration-150"
      onClick={onClose}
    >
      <div
        ref={trapRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-label={t('palette.title', 'Command palette')}
        onClick={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
        className="w-full max-w-lg bg-surface-container rounded-xl ring-1 ring-white/[0.08] shadow-2xl outline-none animate-in zoom-in-95 slide-in-from-top-2 duration-150 overflow-hidden"
      >
        <div className="flex items-center gap-2.5 px-4 border-b border-outline-variant/60">
          <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-outline">search</span>
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t('palette.placeholder', 'Search pages and actions…')}
            aria-label={t('palette.title', 'Command palette')}
            role="combobox"
            aria-expanded="true"
            aria-controls="command-palette-list"
            aria-activedescendant={filtered[activeIndex] ? `cmd-${filtered[activeIndex].id}` : undefined}
            className="flex-1 h-12 bg-transparent text-[14px] text-on-surface placeholder:text-outline focus:outline-none"
          />
          <kbd className="text-[11px] text-outline border border-outline-variant rounded px-1.5 py-0.5">esc</kbd>
        </div>

        <div ref={listRef} id="command-palette-list" role="listbox" className="max-h-[320px] overflow-y-auto py-2">
          {filtered.length === 0 && (
            <p className="px-4 py-6 text-center text-[13px] text-on-surface-variant">
              {t('palette.noResults', 'No matching command')}
            </p>
          )}
          {sections.map(({ key, title }) => {
            const items = filtered.filter((c) => c.section === key);
            if (items.length === 0) return null;
            return (
              <div key={key} className="pb-1">
                <p className="px-4 pt-2 pb-1 text-[11px] font-medium uppercase tracking-[0.05em] text-outline select-none">
                  {title}
                </p>
                {items.map((cmd) => {
                  const index = filtered.indexOf(cmd);
                  const active = index === activeIndex;
                  return (
                    <button
                      key={cmd.id}
                      id={`cmd-${cmd.id}`}
                      type="button"
                      role="option"
                      aria-selected={active}
                      data-index={index}
                      onMouseEnter={() => setActiveIndex(index)}
                      onClick={() => cmd.run()}
                      className={`w-full flex items-center gap-3 px-4 py-2 text-left text-[13px] transition-colors ${
                        active ? 'bg-surface-container-high text-on-surface' : 'text-on-surface-variant'
                      }`}
                    >
                      <span aria-hidden="true" className="material-symbols-outlined text-[18px] text-outline">{cmd.icon}</span>
                      <span className="flex-1 truncate">{cmd.label}</span>
                      {active && (
                        <span aria-hidden="true" className="material-symbols-outlined text-[16px] text-outline">keyboard_return</span>
                      )}
                    </button>
                  );
                })}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

export default CommandPalette;
