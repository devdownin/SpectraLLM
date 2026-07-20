import { useEffect, useRef, useState } from 'react';
import type { FC } from 'react';
import { countUpValue, formatCount } from '../../lib/animation';
import { cn } from './cn';

/** `true` si l'utilisateur a demandé à réduire les animations (préférence système). */
const prefersReducedMotion = (): boolean =>
  typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export interface CountUpProps {
  /** Valeur cible. Quand elle change (ex. polling), le compteur ré-anime depuis l'affichage courant. */
  to: number;
  /** Valeur de départ au premier rendu (défaut 0). */
  from?: number;
  durationMs?: number;
  decimals?: number;
  /** Séparateur de milliers ('' = aucun, défaut). */
  separator?: string;
  prefix?: string;
  suffix?: string;
  className?: string;
}

/**
 * Compteur animé (inspiré de React Bits « CountUp », réécrit sans dépendance) : anime la valeur
 * affichée jusqu'à `to` via requestAnimationFrame. Respecte `prefers-reduced-motion` (affiche
 * directement la valeur finale) et ré-anime depuis l'affichage courant quand `to` change.
 */
export const CountUp: FC<CountUpProps> = ({
  to, from = 0, durationMs = 900, decimals = 0, separator = '', prefix = '', suffix = '', className,
}) => {
  const [value, setValue] = useState(() => (prefersReducedMotion() ? to : from));
  // Départ de la prochaine transition = valeur actuellement affichée (mise à jour à chaque rendu).
  const valueRef = useRef(value);
  valueRef.current = value;
  const rafRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (prefersReducedMotion() || durationMs <= 0) {
      setValue(to);
      return;
    }
    const start = valueRef.current;
    const t0 = performance.now();
    const tick = (now: number) => {
      const elapsed = now - t0;
      setValue(countUpValue(elapsed, durationMs, start, to));
      if (elapsed < durationMs) rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => { if (rafRef.current !== undefined) cancelAnimationFrame(rafRef.current); };
  }, [to, durationMs]);

  return (
    <span className={cn('tabular-nums', className)}>
      {prefix}{formatCount(value, decimals, separator)}{suffix}
    </span>
  );
};

export default CountUp;
