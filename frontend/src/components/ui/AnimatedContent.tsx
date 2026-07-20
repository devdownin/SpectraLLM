import { useEffect, useRef, useState } from 'react';
import type { FC, ReactNode } from 'react';
import { cn } from './cn';

const prefersReducedMotion = (): boolean =>
  typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export interface AnimatedContentProps {
  children: ReactNode;
  className?: string;
  /** Délai avant l'apparition (ms) — utile pour décaler plusieurs blocs en cascade. */
  delayMs?: number;
  /** Distance de translation initiale (px). */
  distance?: number;
  direction?: 'up' | 'down' | 'left' | 'right';
}

/**
 * Enveloppe de révélation (inspirée de React Bits « AnimatedContent », réécrite sans dépendance) :
 * le contenu apparaît en fondu + glissement quand il entre dans le viewport. Dégrade proprement —
 * si `IntersectionObserver` est absent (tests jsdom) ou si l'utilisateur réduit les animations,
 * le contenu est affiché immédiatement, sans transform.
 */
export const AnimatedContent: FC<AnimatedContentProps> = ({
  children, className, delayMs = 0, distance = 16, direction = 'up',
}) => {
  const ref = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState(
    () => prefersReducedMotion() || typeof IntersectionObserver === 'undefined',
  );

  useEffect(() => {
    if (shown) return;
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      entries => {
        if (entries.some(e => e.isIntersecting)) {
          setShown(true);
          io.disconnect();
        }
      },
      { threshold: 0.1 },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [shown]);

  const axis = direction === 'up' || direction === 'down' ? 'translateY' : 'translateX';
  const sign = direction === 'up' || direction === 'left' ? 1 : -1;

  return (
    <div
      ref={ref}
      className={cn('transition-all duration-700 ease-out motion-reduce:transition-none', className)}
      style={{
        opacity: shown ? 1 : 0,
        transform: shown ? 'none' : `${axis}(${sign * distance}px)`,
        transitionDelay: shown ? `${delayMs}ms` : '0ms',
      }}
    >
      {children}
    </div>
  );
};

export default AnimatedContent;
