import { useRef } from 'react';
import type { FC, ReactNode, MouseEvent } from 'react';
import { cn } from './cn';

export interface SpotlightCardProps {
  children: ReactNode;
  className?: string;
  /**
   * Fond du halo — par défaut un voile de la couleur `primary` du thème (via `color-mix`), donc
   * fidèle au clair/sombre. Passe une autre valeur CSS pour un accent différent.
   */
  spotlight?: string;
  /** Rayon du halo (px). */
  radius?: number;
}

/**
 * Carte avec halo suivant le curseur (inspirée de React Bits « SpotlightCard », réécrite sans
 * dépendance) : un dégradé radial révélé au survol, positionné via des variables CSS mises à jour
 * au `mousemove`. Purement décoratif (`aria-hidden`, `pointer-events-none`) et sensible au thème.
 */
export const SpotlightCard: FC<SpotlightCardProps> = ({
  children, className, spotlight, radius = 240,
}) => {
  const ref = useRef<HTMLDivElement>(null);

  const onMove = (e: MouseEvent<HTMLDivElement>) => {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty('--spot-x', `${e.clientX - r.left}px`);
    el.style.setProperty('--spot-y', `${e.clientY - r.top}px`);
  };

  const halo = spotlight ?? 'color-mix(in srgb, var(--color-primary) 14%, transparent)';

  return (
    <div ref={ref} onMouseMove={onMove} className={cn('group relative overflow-hidden', className)}>
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 opacity-0 transition-opacity duration-300 group-hover:opacity-100 motion-reduce:transition-none"
        style={{
          background: `radial-gradient(${radius}px circle at var(--spot-x, 50%) var(--spot-y, 50%), ${halo}, transparent 65%)`,
        }}
      />
      <div className="relative">{children}</div>
    </div>
  );
};

export default SpotlightCard;
