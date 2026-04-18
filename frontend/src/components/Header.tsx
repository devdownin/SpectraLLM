import type { FC } from 'react';
import { toast } from 'sonner';

const Header: FC = () => {
  return (
    <header className="flex justify-between items-center px-6 py-3 sticky top-0 z-50 bg-surface-container shadow-[0_0_15px_rgba(143,245,255,0.04)]">
      <div className="flex items-center gap-4">
        <h1 className="text-xl font-bold tracking-[-0.04em] text-primary font-headline">Spectra</h1>
      </div>
      <div className="flex items-center gap-6">
        <div className="relative hidden lg:block">
          <input
            className="bg-surface-variant border-none text-on-surface text-sm px-4 py-1.5 focus:ring-1 focus:ring-primary w-64 font-body"
            placeholder="Search parameters..."
            type="text"
            onChange={() => {}}
          />
          <span className="material-symbols-outlined absolute right-3 top-1.5 text-on-surface-variant text-sm">
            search
          </span>
        </div>
        <div className="flex items-center gap-4 text-on-surface-variant">
          <button
            className="hover:bg-surface-variant p-1.5 transition-colors duration-200"
            title="Settings"
            onClick={() => toast.info('Settings panel coming soon')}
          >
            <span className="material-symbols-outlined">settings</span>
          </button>
          <button
            className="hover:bg-surface-variant p-1.5 transition-colors duration-200 relative"
            title="Notifications"
            onClick={() => toast.info('No new notifications')}
          >
            <span className="material-symbols-outlined">notifications</span>
            <span className="absolute top-1 right-1 w-2 h-2 bg-secondary rounded-full"></span>
          </button>
          <button
            className="hover:bg-surface-variant p-1.5 transition-colors duration-200"
            title="Account"
            onClick={() => toast.info('Account management coming soon')}
          >
            <span className="material-symbols-outlined">account_circle</span>
          </button>
        </div>
      </div>
    </header>
  );
};

export default Header;
