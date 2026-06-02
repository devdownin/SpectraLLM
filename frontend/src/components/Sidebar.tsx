import type { FC } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import Tooltip from './Tooltip';

interface SidebarProps {
  isCollapsed: boolean;
  onToggle: () => void;
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

const Sidebar: FC<SidebarProps> = ({ isCollapsed, onToggle }) => {
  const navigate = useNavigate();

  const navItems = [
    { name: 'Dashboard',  icon: 'dashboard',      path: '/'             },
    { name: 'Model Hub',  icon: 'hub',             path: '/model-hub'    },
    { name: 'Datasets',   icon: 'database',        path: '/datasets'     },
    { name: 'Database',   icon: 'analytics',       path: '/pipelines'    },
    { name: 'Fine-Tuning',icon: 'history',         path: '/fine-tuning'  },
    { name: 'Playground', icon: 'chat_bubble',     path: '/playground'   },
    { name: 'Comparison', icon: 'compare_arrows',  path: '/comparison'   },
  ];

  return (
    <aside className={`sidebar-dots fixed left-0 top-0 h-full flex flex-col p-4 z-40 bg-surface-container-low transition-all duration-300 ${isCollapsed ? 'w-20' : 'w-64'} border-r border-outline-variant/10`}>
      {/* Logo */}
      <div className="flex items-center justify-between mb-8 overflow-hidden">
        {!isCollapsed && (
          <div className="flex items-center gap-2.5 animate-in fade-in duration-500">
            <span className="text-primary shrink-0">
              <NeuralIcon size={20} />
            </span>
            <div>
              <div className="text-base font-black text-primary font-headline tracking-[-0.04em] leading-none">Spectra</div>
              <div className="font-headline uppercase tracking-[0.12em] text-[9px] text-outline mt-0.5">AI ARCHITECT</div>
            </div>
          </div>
        )}
        {isCollapsed && (
          <span className="text-primary mx-auto">
            <NeuralIcon size={22} />
          </span>
        )}
        {!isCollapsed && (
          <Tooltip content="">
            <button
              onClick={onToggle}
              className="p-1.5 hover:bg-surface-variant/60 text-outline hover:text-primary transition-colors shrink-0"
            >
              <span className="material-symbols-outlined text-[18px]">menu</span>
            </button>
          </Tooltip>
        )}
      </div>

      {/* Collapse toggle when collapsed */}
      {isCollapsed && (
        <Tooltip content="Expand">
          <button
            onClick={onToggle}
            className="mx-auto mb-6 p-1.5 hover:bg-surface-variant/60 text-outline hover:text-primary transition-colors"
          >
            <span className="material-symbols-outlined text-[18px]">menu_open</span>
          </button>
        </Tooltip>
      )}

      <nav className="flex-1 space-y-0.5">
        {navItems.map((item) => (
          <Tooltip key={item.path} content={isCollapsed ? item.name : ''}>
            <NavLink
              to={item.path}
              end={item.path === '/'}
              className={({ isActive }) =>
                `flex items-center transition-all duration-150 cursor-pointer w-full ${isCollapsed ? 'px-3 py-3 justify-center' : 'px-4 py-3'} ${
                  isActive
                    ? 'text-primary nav-active'
                    : 'text-outline hover:text-on-surface hover:bg-surface-container-high/60'
                }`
              }
            >
              <span className={`material-symbols-outlined text-[18px] ${isCollapsed ? 'mr-0' : 'mr-3'}`}>{item.icon}</span>
              {!isCollapsed && (
                <span className="font-headline uppercase tracking-[0.08em] text-[11px] animate-in fade-in slide-in-from-left-2">
                  {item.name}
                </span>
              )}
            </NavLink>
          </Tooltip>
        ))}
      </nav>

      <Tooltip content={isCollapsed ? 'New Model' : ''}>
        <button
          onClick={() => navigate('/fine-tuning')}
          className={`mt-4 mb-6 bg-primary text-on-primary-fixed font-bold flex items-center justify-center transition-all hover:opacity-90 hover:shadow-[0_0_20px_rgba(143,245,255,0.25)] ${isCollapsed ? 'w-10 h-10 mx-auto' : 'py-2.5 px-4 gap-2'}`}
        >
          <span className="material-symbols-outlined text-[16px]">add</span>
          {!isCollapsed && <span className="font-headline uppercase tracking-[0.1em] text-[10px]">NEW MODEL</span>}
        </button>
      </Tooltip>

      <div className="space-y-0.5 mt-auto pt-4 border-t border-outline-variant/10">
        <Tooltip content={isCollapsed ? 'Documentation' : ''}>
          <NavLink
            to="/documentation"
            className={({ isActive }) =>
              `flex items-center text-outline hover:text-primary hover:bg-surface-container-high/60 transition-all cursor-pointer w-full ${isCollapsed ? 'px-3 py-2 justify-center' : 'px-4 py-2'} ${isActive ? 'text-primary nav-active' : ''}`
            }
          >
            <span className={`material-symbols-outlined text-[18px] ${isCollapsed ? 'mr-0' : 'mr-3'}`}>menu_book</span>
            {!isCollapsed && <span className="font-headline uppercase tracking-[0.08em] text-[11px] animate-in fade-in slide-in-from-left-2">Docs</span>}
          </NavLink>
        </Tooltip>
      </div>
    </aside>
  );
};

export default Sidebar;
