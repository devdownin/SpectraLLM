import type { FC } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { NAV_BY_PATH } from '../navigation';
import TaskCenter from './TaskCenter';
import { useStatus } from '../hooks/useStatus';

interface HeaderProps {
  onMenuClick?: () => void;
}

const Header: FC<HeaderProps> = ({ onMenuClick }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const { t, i18n } = useTranslation();
  const navItem = NAV_BY_PATH[location.pathname];
  const pageName = navItem ? t(navItem.nameKey, navItem.name) : '';

  const currentLang = i18n.resolvedLanguage === 'fr' ? 'fr' : 'en';
  const nextLang = currentLang === 'fr' ? 'en' : 'fr';
  const { status } = useStatus();

  const chatStatus = status?.services?.find((s: any) => s.name === 'llama-cpp');
  const embedStatus = status?.services?.find((s: any) => s.name === 'llama-cpp-embed');

  const getStatusColor = (s: any) => {
    if (!s) return 'bg-outline'; // Unknown
    if (!s.available) return 'bg-error'; // Missing/Down
    if (s.details?.activeModelLoaded === false) return 'bg-warning animate-pulse'; // Loading
    return 'bg-success'; // OK
  };


  return (
    <header className="header-border flex justify-between items-center px-4 md:px-6 py-3 sticky top-0 z-30 bg-surface-container/80 backdrop-blur-md">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={onMenuClick}
          aria-label="Open navigation"
          className="md:hidden p-1.5 -ml-1.5 hover:bg-surface-variant/60 text-on-surface-variant hover:text-primary transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[22px]">menu</span>
        </button>
        <span className="font-label text-[10px] uppercase tracking-[0.15em] text-outline select-none">Spectra</span>
        {pageName && (
          <>
            <span className="text-outline/40 text-[11px]">/</span>
            <span className="font-headline font-bold text-[11px] uppercase tracking-[0.08em] text-on-surface">{pageName}</span>
          </>
        )}
      </div>
      <div className="flex items-center gap-1">
        <div className="flex items-center gap-2 mr-4 border-r border-border pr-4 hidden md:flex">
          <div className="flex items-center gap-1.5" title={`Chat Server\nStatus: ${chatStatus?.available ? (chatStatus.details?.activeModelLoaded === false ? 'Loading Model' : 'Ready') : 'Offline'}\nModel: ${chatStatus?.details?.activeModel || 'None'}`}>
            <div className={`w-2 h-2 rounded-full ${getStatusColor(chatStatus)}`} />
            <span className="text-[10px] text-muted-foreground uppercase tracking-wider">Chat</span>
          </div>
          <div className="flex items-center gap-1.5" title={`Embedding Server\nStatus: ${embedStatus?.available ? (embedStatus.details?.activeModelLoaded === false ? 'Loading Model' : 'Ready') : 'Offline'}\nModel: ${embedStatus?.details?.activeModel || 'None'}`}>
            <div className={`w-2 h-2 rounded-full ${getStatusColor(embedStatus)}`} />
            <span className="text-[10px] text-muted-foreground uppercase tracking-wider">Embed</span>
          </div>
        </div>
      <TaskCenter />
      {navItem?.docSection && (
        <button
          type="button"
          onClick={() => navigate(`/documentation?section=${navItem.docSection}`)}
          aria-label={t('header.help')}
          title={t('header.help')}
          className="p-1.5 hover:bg-surface-variant/60 text-on-surface-variant hover:text-primary transition-colors"
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[18px]">help</span>
        </button>
      )}
      <button
        type="button"
        onClick={() => i18n.changeLanguage(nextLang)}
        aria-label={t('header.switchLanguage')}
        title={t('header.switchLanguage')}
        className="flex items-center gap-1.5 px-2 py-1.5 hover:bg-surface-variant/60 text-on-surface-variant hover:text-primary transition-colors"
      >
        <span aria-hidden="true" className="material-symbols-outlined text-[18px]">language</span>
        <span className="font-headline font-bold text-[11px] uppercase tracking-widest">{currentLang}</span>
      </button>
      </div>
    </header>
  );
};

export default Header;
