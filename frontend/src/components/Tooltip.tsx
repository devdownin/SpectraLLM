import { useState } from 'react';
import type { FC, ReactNode } from 'react';

interface TooltipProps {
  content: string;
  children: ReactNode;
}

const Tooltip: FC<TooltipProps> = ({ content, children }) => {
  const [isVisible, setIsVisible] = useState(false);

  // Sans contenu, ne pas injecter de wrapper supplémentaire dans le DOM.
  if (!content) return <>{children}</>;

  return (
    <div
      className="relative flex items-center group"
      onMouseEnter={() => setIsVisible(true)}
      onMouseLeave={() => setIsVisible(false)}
      onFocus={() => setIsVisible(true)}
      onBlur={() => setIsVisible(false)}
    >
      {children}
      {isVisible && (
        <div role="tooltip" className="absolute z-[100] left-full ml-2 px-3 py-2 bg-inverse-surface text-inverse-on-surface text-[11px] font-label uppercase tracking-widest border border-primary/20 shadow-xl whitespace-nowrap animate-in fade-in slide-in-from-left-1 duration-200">
          {content}
          {/* Arrow */}
          <div className="absolute top-1/2 -left-1 -translate-y-1/2 border-y-4 border-y-transparent border-r-4 border-r-inverse-surface"></div>
        </div>
      )}
    </div>
  );
};

export default Tooltip;
