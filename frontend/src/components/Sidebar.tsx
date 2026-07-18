import type { FC } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import Tooltip from './Tooltip';
import { NAV_ITEMS, DOCUMENTATION_ITEM } from '../navigation';
import type { NavItem } from '../navigation';

interface SidebarProps {
  isCollapsed: boolean;
  onToggle: () => void;
  mobileOpen?: boolean;
  onMobileClose?: () => void;
}

const NeuralIcon: FC<{ size?: number }> = ({ size = 22 }) => (
  <svg width={size} height={size} viewBox="0 0 22 22" fill="none" aria-hidden="true">
    <circle cx="3.5" cy="5"   r="1.8" fill="currentColor" opacity="0.65" />
    <circle cx="3.5" cy="11"  r="1.8" fill="currentColor" opacity="0.65" />
    <circle cx="3.5" cy="17"  r="1.8" fill="currentColor" opacity="0.65" />
    <circle cx="11"  cy="7.5" r="1.8" fill="currentColor" />
    <circle cx="11"  cy="14.5"r="1.8" fill="currentColor" />
    <circle cx="18.5"cy="11"  r="1.8" fill="currentColor" opacity="0.65" />
    <line x1="5.3"  y1="5"    x2="9.2" y2="7.5"  stroke="currentColor" strokeWidth="0.8" opacity="0.35" />
    <line x1="5.3"  y1="11"   x2="9.2" y2="7.5"  stroke="currentColor" strokeWidth="0.8" opacity="0.35" />
    <line x1="5.3"  y1="11"   x2="9.2" y2="14.5" stroke="currentColor" strokeWidth="0.8" opacity="0.35" />
    <line x1="5.3"  y1="17"   x2="9.2" y2="14.5" stroke="currentColor" strokeWidth="0.8" opacity="0.35" />
    <line x1="12.8" y1="7.5"  x2="16.7"y2="11"   stroke="currentColor" strokeWidth="0.8" opacity="0.35" />
    <line x1="12.8" y1="14.5" x2="16.7"y2="11"   stroke="currentColor" strokeWidth="0.8" opacity="0.35" />
  </svg>
);

/** Regroupe les entrées de navigation en sections ordonnées (groupKey → items). */
function groupNavItems(items: NavItem[]): { groupKey?: string; items: NavItem[] }[] {
  const sections: { groupKey?: string; items: NavItem[] }[] = [];
  for (const item of items) {
    const last = sections[sections.length - 1];
    if (last && last.groupKey === item.groupKey) {
      last.items.push(item);
    } else {
      sections.push({ groupKey: item.groupKey, items: [item] });
    }
  }
  return sections;
}

const Sidebar: FC<SidebarProps> = ({ isCollapsed, onToggle, mobileOpen = false, onMobileClose }) => {
  const navigate = useNavigate();
  const { t } = useTranslation();

  const sections = groupNavItems(NAV_ITEMS);

  const linkClasses = (isActive: boolean) =>
    `group flex items-center rounded-lg transition-colors duration-150 cursor-pointer w-full ` +
    `${isCollapsed ? 'px-0 py-2.5 justify-center' : 'px-3 py-2'} ` +
    (isActive
      ? 'nav-active text-on-surface'
      : 'text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high/70');

  return (
    <aside
      className={`fixed left-0 top-0 h-full flex flex-col px-3 py-4 z-50 bg-surface-container-low transition-all duration-300 w-64 ${
        isCollapsed ? 'md:w-[68px]' : 'md:w-64'
      } ${mobileOpen ? 'translate-x-0' : '-translate-x-full'} md:translate-x-0 border-r border-outline-variant/60`}
    >
      {/* Logo */}
      <div className={`flex items-center mb-6 ${isCollapsed ? 'justify-center' : 'justify-between px-1'}`}>
        {!isCollapsed && (
          <div className="flex items-center gap-2.5 animate-in fade-in duration-300">
            <span className="text-primary shrink-0">
              <NeuralIcon size={20} />
            </span>
            <span className="text-[15px] font-semibold tracking-tight text-on-surface leading-none">Spectra</span>
          </div>
        )}
        {isCollapsed && (
          <span className="text-primary">
            <NeuralIcon size={20} />
          </span>
        )}
        {!isCollapsed && (
          <button
            onClick={onToggle}
            aria-label="Collapse sidebar"
            className="p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors shrink-0"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[18px]">left_panel_close</span>
          </button>
        )}
      </div>

      {isCollapsed && (
        <Tooltip content={t('nav.expand', 'Expand')}>
          <button
            onClick={onToggle}
            aria-label="Expand sidebar"
            className="mx-auto mb-4 p-1.5 rounded-md hover:bg-surface-container-high text-outline hover:text-on-surface transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[18px]">left_panel_open</span>
          </button>
        </Tooltip>
      )}

      <nav className="flex-1 overflow-y-auto space-y-1 min-h-0">
        {sections.map((section, i) => (
          <div key={section.groupKey ?? `top-${i}`} className={i > 0 ? 'pt-4' : ''}>
            {section.groupKey && !isCollapsed && (
              <p className="px-3 pb-1.5 text-[11px] font-medium uppercase tracking-[0.05em] text-outline select-none">
                {t(section.groupKey)}
              </p>
            )}
            {section.groupKey && isCollapsed && i > 0 && (
              <div className="mx-3 mb-3 border-t border-outline-variant/60" aria-hidden="true" />
            )}
            <div className="space-y-0.5">
              {section.items.map((item) => (
                <Tooltip key={item.path} content={isCollapsed ? t(item.nameKey, item.name) : ''}>
                  <NavLink
                    to={item.path}
                    end={item.path === '/'}
                    onClick={onMobileClose}
                    className={({ isActive }) => linkClasses(isActive)}
                  >
                    <span aria-hidden="true" className={`material-symbols-outlined text-[19px] ${isCollapsed ? 'mr-0' : 'mr-2.5'}`}>
                      {item.icon}
                    </span>
                    {!isCollapsed && (
                      <span className="text-[13px] font-medium">
                        {t(item.nameKey, item.name)}
                      </span>
                    )}
                  </NavLink>
                </Tooltip>
              ))}
            </div>
          </div>
        ))}
      </nav>

      <Tooltip content={isCollapsed ? t('nav.newModel') : ''}>
        <button
          onClick={() => { navigate('/fine-tuning'); onMobileClose?.(); }}
          className={`mt-4 mb-4 bg-primary text-on-primary rounded-lg font-medium flex items-center justify-center transition-all hover:bg-primary-fixed active:scale-[0.99] ${
            isCollapsed ? 'w-10 h-10 mx-auto' : 'py-2 px-3 gap-2'
          }`}
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">add</span>
          {!isCollapsed && <span className="text-[13px]">{t('nav.newModel')}</span>}
        </button>
      </Tooltip>

      <div className="mt-auto pt-3 border-t border-outline-variant/60">
        <Tooltip content={isCollapsed ? t(DOCUMENTATION_ITEM.nameKey, DOCUMENTATION_ITEM.name) : ''}>
          <NavLink
            to={DOCUMENTATION_ITEM.path}
            onClick={onMobileClose}
            className={({ isActive }) => linkClasses(isActive)}
          >
            <span aria-hidden="true" className={`material-symbols-outlined text-[19px] ${isCollapsed ? 'mr-0' : 'mr-2.5'}`}>
              {DOCUMENTATION_ITEM.icon}
            </span>
            {!isCollapsed && <span className="text-[13px] font-medium">{t('nav.docs')}</span>}
          </NavLink>
        </Tooltip>
      </div>
    </aside>
  );
};

export default Sidebar;
