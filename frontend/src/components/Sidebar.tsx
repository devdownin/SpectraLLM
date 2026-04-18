import type { FC } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import Tooltip from './Tooltip';

interface SidebarProps {
  isCollapsed: boolean;
  onToggle: () => void;
}

const Sidebar: FC<SidebarProps> = ({ isCollapsed, onToggle }) => {
  const navigate = useNavigate();

  const navItems = [
    { name: 'Dashboard', icon: 'dashboard', path: '/' },
    { name: 'Model Hub', icon: 'hub', path: '/model-hub' },
    { name: 'Datasets', icon: 'database', path: '/datasets' },
    { name: 'Database', icon: 'analytics', path: '/pipelines' },
    { name: 'Fine-Tuning', icon: 'history', path: '/fine-tuning' },
    { name: 'Playground', icon: 'chat_bubble', path: '/playground' },
    { name: 'Comparison', icon: 'compare_arrows', path: '/comparison' },
  ];

  return (
    <aside className={`fixed left-0 top-0 h-full flex flex-col p-4 z-40 bg-surface-container-low transition-all duration-300 ${isCollapsed ? 'w-20' : 'w-64'} shadow-none border-r border-outline-variant/10`}>
      <div className="flex items-center justify-between mb-8 overflow-hidden">
        {!isCollapsed && (
          <div className="animate-in fade-in duration-500">
            <div className="text-lg font-black text-primary mb-1 font-headline tracking-[-0.04em]">Spectra</div>
            <div className="font-headline uppercase tracking-[0.1em] text-[11px] text-outline">AI ARCHITECT</div>
          </div>
        )}
        <Tooltip content={isCollapsed ? 'Expand' : ''}>
          <button
            onClick={onToggle}
            className={`p-2 hover:bg-surface-variant text-outline hover:text-primary transition-colors ${isCollapsed ? 'mx-auto' : ''}`}
          >
            <span className="material-symbols-outlined">
              {isCollapsed ? 'menu_open' : 'menu'}
            </span>
          </button>
        </Tooltip>
      </div>

      <nav className="flex-1 space-y-1">
        {navItems.map((item) => (
          <Tooltip key={item.path} content={isCollapsed ? item.name : ''}>
            <NavLink
              to={item.path}
              className={({ isActive }) =>
                `flex items-center transition-all cursor-pointer w-full ${isCollapsed ? 'px-3 py-3 justify-center' : 'px-4 py-3'} ${
                  isActive
                    ? 'text-primary border-l-2 border-secondary bg-surface-variant'
                    : 'text-outline hover:text-primary hover:bg-surface-container-high'
                }`
              }
            >
              <span className={`material-symbols-outlined ${isCollapsed ? 'mr-0' : 'mr-3'}`}>{item.icon}</span>
              {!isCollapsed && <span className="font-headline uppercase tracking-[0.1em] text-[11px] animate-in fade-in slide-in-from-left-2">{item.name}</span>}
            </NavLink>
          </Tooltip>
        ))}
      </nav>

      <Tooltip content={isCollapsed ? 'New Model' : ''}>
        <button
          onClick={() => navigate('/fine-tuning')}
          className={`mt-4 mb-8 bg-primary text-on-primary-fixed font-bold flex items-center justify-center transition-all hover:opacity-90 ${isCollapsed ? 'w-10 h-10 mx-auto' : 'py-3 px-4 gap-2'}`}
        >
          <span className="material-symbols-outlined">add</span>
          {!isCollapsed && <span className="font-headline uppercase tracking-[0.1em] text-[11px]">NEW MODEL</span>}
        </button>
      </Tooltip>

      <div className="space-y-1 mt-auto pt-4 border-t border-outline-variant/10">
        <Tooltip content={isCollapsed ? 'Documentation' : ''}>
          <NavLink
            to="/documentation"
            className={`flex items-center text-outline hover:text-primary hover:bg-surface-container-high transition-all cursor-pointer w-full ${isCollapsed ? 'px-3 py-2 justify-center' : 'px-4 py-2'}`}
          >
            <span className={`material-symbols-outlined ${isCollapsed ? 'mr-0' : 'mr-3'}`}>menu_book</span>
            {!isCollapsed && <span className="font-headline uppercase tracking-[0.1em] text-[11px] animate-in fade-in slide-in-from-left-2">Docs</span>}
          </NavLink>
        </Tooltip>
      </div>
    </aside>
  );
};

export default Sidebar;
