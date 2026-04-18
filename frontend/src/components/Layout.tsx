import { useState } from 'react';
import type { FC, ReactNode } from 'react';
import { Toaster } from 'sonner';
import Sidebar from './Sidebar';
import Header from './Header';
import WizardProgress from './WizardProgress';

interface LayoutProps {
  children: ReactNode;
}

const Layout: FC<LayoutProps> = ({ children }) => {
  const [isCollapsed, setIsCollapsed] = useState(false);

  return (
    <div className="min-h-screen bg-background">
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
            fontSize: '12px',
            textTransform: 'uppercase',
            letterSpacing: '0.05em'
          }
        }} 
      />
      
      <Sidebar isCollapsed={isCollapsed} onToggle={() => setIsCollapsed(!isCollapsed)} />
      
      <main className={`transition-all duration-300 ${isCollapsed ? 'ml-20' : 'ml-64'} min-h-screen flex flex-col`}>
        <Header />
        <WizardProgress />
        <div className="flex-1 p-8 max-w-[1600px] mx-auto w-full">
          {children}
        </div>
      </main>
    </div>
  );
};

export default Layout;
