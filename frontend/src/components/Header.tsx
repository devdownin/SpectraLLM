import type { FC } from 'react';
import { useLocation } from 'react-router-dom';
import { PAGE_NAMES } from '../navigation';

interface HeaderProps {
  onMenuClick?: () => void;
}

const Header: FC<HeaderProps> = ({ onMenuClick }) => {
  const location = useLocation();
  const pageName = PAGE_NAMES[location.pathname] ?? '';

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
        <span className="font-label text-[9px] uppercase tracking-[0.15em] text-outline select-none">Spectra</span>
        {pageName && (
          <>
            <span className="text-outline/40 text-[10px]">/</span>
            <span className="font-headline font-bold text-[11px] uppercase tracking-[0.08em] text-on-surface">{pageName}</span>
          </>
        )}
      </div>
    </header>
  );
};

export default Header;
