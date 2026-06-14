import type { FC } from 'react';
import { useLocation } from 'react-router-dom';
import { toast } from 'sonner';

const PAGE_NAMES: Record<string, string> = {
  '/':              'Dashboard',
  '/model-hub':     'Model Hub',
  '/datasets':      'Datasets',
  '/pipelines':     'Database',
  '/fine-tuning':   'Fine-Tuning',
  '/playground':    'Playground',
  '/comparison':    'Comparison',
  '/documentation': 'Documentation',
};

const Header: FC = () => {
  const location = useLocation();
  const pageName = PAGE_NAMES[location.pathname] ?? '';

  return (
    <header className="header-border flex justify-between items-center px-6 py-3 sticky top-0 z-50 bg-surface-container/80 backdrop-blur-md">
      <div className="flex items-center gap-3">
        <span className="font-label text-[9px] uppercase tracking-[0.15em] text-outline select-none">Spectra</span>
        {pageName && (
          <>
            <span className="text-outline/40 text-[10px]">/</span>
            <span className="font-headline font-bold text-[11px] uppercase tracking-[0.08em] text-on-surface">{pageName}</span>
          </>
        )}
      </div>
      <div className="flex items-center gap-6">
        <div className="relative hidden lg:block">
          <label htmlFor="header-search" className="sr-only">Search parameters</label>
          <input
            id="header-search"
            className="bg-surface-variant/60 border border-outline-variant/20 text-on-surface text-sm px-4 py-1.5 focus:outline-none focus:border-primary/40 w-64 font-body transition-colors placeholder:text-outline/50"
            placeholder="Search parameters..."
            type="text"
            onChange={() => {}}
          />
          <span aria-hidden="true" className="material-symbols-outlined absolute right-3 top-1.5 text-outline/50 text-sm">
            search
          </span>
        </div>
        <div className="flex items-center gap-3 text-on-surface-variant">
          <button
            className="hover:bg-surface-variant/60 p-1.5 transition-colors duration-200 hover:text-primary"
            aria-label="Settings"
            title="Settings"
            onClick={() => toast.info('Settings panel coming soon')}
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[20px]">settings</span>
          </button>
          <button
            className="hover:bg-surface-variant/60 p-1.5 transition-colors duration-200 relative hover:text-primary"
            aria-label="Notifications"
            title="Notifications"
            onClick={() => toast.info('No new notifications')}
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[20px]">notifications</span>
            <span aria-hidden="true" className="absolute top-1 right-1 w-1.5 h-1.5 bg-secondary rounded-full"></span>
          </button>
          <button
            className="hover:bg-surface-variant/60 p-1.5 transition-colors duration-200 hover:text-primary"
            aria-label="Account"
            title="Account"
            onClick={() => toast.info('Account management coming soon')}
          >
            <span aria-hidden="true" className="material-symbols-outlined text-[20px]">account_circle</span>
          </button>
        </div>
      </div>
    </header>
  );
};

export default Header;
