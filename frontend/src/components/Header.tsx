import type { FC } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { NAV_BY_PATH } from '../navigation';
import TaskCenter from './TaskCenter';
import { useStatus } from '../hooks/useStatus';

interface HeaderProps {
  onMenuClick?: () => void;
  /** Ouvre la palette de commandes (⌘K). */
  onSearchClick?: () => void;
}

interface ServiceInfo {
  name: string;
  available?: boolean;
  details?: { activeModel?: string; activeModelLoaded?: boolean };
}

const Header: FC<HeaderProps> = ({ onMenuClick, onSearchClick }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();
  const navItem = NAV_BY_PATH[location.pathname];
  const pageName = navItem ? t(navItem.nameKey, navItem.name) : '';

  const currentLang = i18n.resolvedLanguage === 'fr' ? 'fr' : 'en';
  const nextLang = currentLang === 'fr' ? 'en' : 'fr';
  const { status } = useStatus();

  const services: ServiceInfo[] = status?.services ?? [];
  const chatStatus = services.find((s) => s.name === 'llama-cpp');
  const embedStatus = services.find((s) => s.name === 'llama-cpp-embed');

  const getStatusColor = (s?: ServiceInfo) => {
    if (!s) return 'bg-outline';
    if (!s.available) return 'bg-error';
    if (s.details?.activeModelLoaded === false) return 'bg-warning animate-pulse';
    return 'bg-success';
  };

  const statusTitle = (label: string, s?: ServiceInfo) =>
    `${label}\nStatus: ${s?.available ? (s.details?.activeModelLoaded === false ? 'Loading model' : 'Ready') : 'Offline'}\nModel: ${s?.details?.activeModel || 'None'}`;

  return (
    <header className="header-border flex justify-between items-center px-4 md:px-6 h-14 sticky top-0 z-30 bg-surface/80 backdrop-blur-md">
      <div className="flex items-center gap-2 min-w-0">
        <button
          type="button"
          onClick={onMenuClick}
          aria-label="Open navigation"
          className="md:hidden p-1.5 -ml-1.5 rounded-md hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[22px]">menu</span>
        </button>
        <nav aria-label="Breadcrumb" className="flex items-center gap-2 min-w-0">
          <span className="text-[13px] text-on-surface-variant select-none">Spectra</span>
          {pageName && (
            <>
              <span aria-hidden="true" className="text-outline-variant text-[13px]">/</span>
              <span className="text-[13px] font-medium text-on-surface truncate">{pageName}</span>
            </>
          )}
        </nav>
      </div>

      <div className="flex items-center gap-1">
        {/* État des services (desktop) */}
        <div className="hidden md:flex items-center gap-3 mr-3 pr-3 border-r border-outline-variant/60">
          <div className="flex items-center gap-1.5" title={statusTitle('Chat server', chatStatus)}>
            <div className={`w-1.5 h-1.5 rounded-full ${getStatusColor(chatStatus)}`} />
            <span className="text-[12px] text-on-surface-variant">Chat</span>
            {chatStatus?.details?.activeModel && (
              // Raccourci vers le Playground, où l'on change de modèle actif.
              <button
                type="button"
                onClick={() => navigate('/playground')}
                title={t('header.activeModelHint')}
                aria-label={t('header.activeModelHint')}
                className="text-[12px] font-mono text-primary max-w-[160px] truncate hover:underline underline-offset-2 transition-colors"
              >
                {chatStatus.details.activeModel}
              </button>
            )}
          </div>
          <div className="flex items-center gap-1.5" title={statusTitle('Embedding server', embedStatus)}>
            <div className={`w-1.5 h-1.5 rounded-full ${getStatusColor(embedStatus)}`} />
            <span className="text-[12px] text-on-surface-variant">Embed</span>
          </div>
        </div>

        {onSearchClick && (
          <button
            type="button"
            onClick={onSearchClick}
            aria-label={t('palette.title', 'Command palette')}
            title={t('palette.title', 'Command palette')}
            className="hidden sm:flex items-center gap-2 h-8 px-2.5 mr-1 rounded-md border border-outline-variant/60 text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[16px]">search</span>
            <span className="text-[12px]">{t('palette.searchShort', 'Search')}</span>
            <kbd className="text-[10px] text-outline border border-outline-variant rounded px-1 py-px">⌘K</kbd>
          </button>
        )}

        <TaskCenter />

        {navItem?.docSection && (
          <button
            type="button"
            onClick={() => navigate(`/documentation?section=${navItem.docSection}`)}
            aria-label={t('header.help')}
            title={t('header.help')}
            className="p-1.5 rounded-md hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors"
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[18px]">help</span>
          </button>
        )}

        <button
          type="button"
          onClick={() => i18n.changeLanguage(nextLang)}
          aria-label={t('header.switchLanguage')}
          title={t('header.switchLanguage')}
          className="flex items-center gap-1.5 px-2 py-1.5 rounded-md hover:bg-surface-container-high text-on-surface-variant hover:text-on-surface transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">language</span>
          <span className="text-[12px] font-medium uppercase">{currentLang}</span>
        </button>
      </div>
    </header>
  );
};

export default Header;
