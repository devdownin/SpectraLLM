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
        <div role="tooltip" className="absolute z-[100] left-full ml-2 px-2.5 py-1.5 rounded-md bg-surface-container-highest text-on-surface text-[12px] border border-outline-variant shadow-lg whitespace-nowrap animate-in fade-in slide-in-from-left-1 duration-200">
          {content}
        </div>
      )}
    </div>
  );
};

export default Tooltip;
