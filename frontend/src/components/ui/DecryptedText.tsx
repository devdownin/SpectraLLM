import { useEffect, useRef, useState } from 'react';
import type { FC } from 'react';
import { scrambleReveal } from '../../lib/animation';
import { cn } from './cn';

const prefersReducedMotion = (): boolean =>
  typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export interface DecryptedTextProps {
  text: string;
  /** Durée totale du déchiffrement (ms). ≤ 0 = texte final immédiat (déterministe / tests). */
  durationMs?: number;
  charset?: string;
  className?: string;
}

/**
 * Effet « déchiffrement » (inspiré de React Bits « DecryptedText », réécrit sans dépendance) :
 * le texte se révèle caractère par caractère, les positions non révélées affichant du bruit.
 * Respecte `prefers-reduced-motion` (affiche le texte final) et reste accessible — le vrai texte
 * est exposé via `aria-label`, le bruit visible est `aria-hidden`.
 */
export const DecryptedText: FC<DecryptedTextProps> = ({ text, durationMs = 1100, charset, className }) => {
  const [display, setDisplay] = useState(() =>
    prefersReducedMotion() || durationMs <= 0 ? text : scrambleReveal(text, 0, charset));
  const rafRef = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (prefersReducedMotion() || durationMs <= 0) {
      setDisplay(text);
      return;
    }
    const t0 = performance.now();
    const tick = (now: number) => {
      const progress = Math.min(1, (now - t0) / durationMs);
      const revealed = Math.floor(progress * text.length);
      setDisplay(scrambleReveal(text, revealed, charset));
      if (progress < 1) rafRef.current = requestAnimationFrame(tick);
      else setDisplay(text);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => { if (rafRef.current !== undefined) cancelAnimationFrame(rafRef.current); };
  }, [text, durationMs, charset]);

  return (
    <span aria-label={text} className={cn('tabular-nums', className)}>
      <span aria-hidden="true">{display}</span>
    </span>
  );
};

export default DecryptedText;
