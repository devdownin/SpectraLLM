import { useEffect, useState } from 'react';
import type { FC, ReactNode } from 'react';
import { Toaster } from 'sonner';
import Sidebar from './Sidebar';
import Header from './Header';
import WizardProgress from './WizardProgress';
import ServiceHealthBanner from './ServiceHealthBanner';

interface LayoutProps {
  children: ReactNode;
}

const DESKTOP_QUERY = '(min-width: 768px)';

const Layout: FC<LayoutProps> = ({ children }) => {
  const [isCollapsed, setIsCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [isDesktop, setIsDesktop] = useState(
    () => typeof window !== 'undefined' && window.matchMedia(DESKTOP_QUERY).matches,
  );

  // Suit le breakpoint : ferme le drawer en repassant desktop.
  useEffect(() => {
    const mq = window.matchMedia(DESKTOP_QUERY);
    const handler = (e: MediaQueryListEvent) => {
      setIsDesktop(e.matches);
      if (e.matches) setMobileOpen(false);
    };
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);

  // Sur mobile, la sidebar est un drawer toujours déployé (jamais en mode "collapsed").
  const effectiveCollapsed = isDesktop ? isCollapsed : false;

  return (
    <div className="min-h-screen bg-background bg-scene">
      <div className="accent-bar" />
      <Toaster
        theme="dark"
        position="bottom-right"
        toastOptions={{
          style: {
            background: '#192540',
            border: '1px solid rgba(143, 245, 255, 0.1)',
            borderRadius: '0px',
            color: '#dee5ff',
            fontFamily: 'Inter',
            // Les toasts portent de l'information (erreurs, confirmations) : taille
            // lisible et casse normale — le style micro-caps reste réservé aux
            // étiquettes de l'interface (constat #22 de l'audit).
            fontSize: '13px',
          }
        }}
      />

      <Sidebar
        isCollapsed={effectiveCollapsed}
        onToggle={() => setIsCollapsed(!isCollapsed)}
        mobileOpen={mobileOpen}
        onMobileClose={() => setMobileOpen(false)}
      />

      {/* Backdrop du drawer mobile */}
      {mobileOpen && (
        <div
          className="fixed inset-0 bg-black/60 backdrop-blur-sm z-40 md:hidden animate-in fade-in duration-200"
          onClick={() => setMobileOpen(false)}
          aria-hidden="true"
        />
      )}

      <main className={`transition-all duration-300 ml-0 ${isCollapsed ? 'md:ml-20' : 'md:ml-64'} min-h-screen flex flex-col`}>
        <Header onMenuClick={() => setMobileOpen(true)} />
        <ServiceHealthBanner />
        <WizardProgress />
        <div className="flex-1 p-4 md:p-8 max-w-[1600px] mx-auto w-full">
          {children}
        </div>
      </main>
    </div>
  );
};

export default Layout;
