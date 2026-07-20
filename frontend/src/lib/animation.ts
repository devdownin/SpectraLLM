/**
 * Logique d'animation pure (easing, valeur d'un compteur, formatage), extraite des composants
 * pour être testable hors DOM. Inspirée des composants React Bits (CountUp), réécrite sans
 * dépendance et adaptée aux conventions du projet.
 */

/** Ease-out cubique : rapide au début, ralentit à l'approche de la cible. t est borné à [0, 1]. */
export const easeOutCubic = (t: number): number => {
  const c = Math.min(1, Math.max(0, t));
  return 1 - Math.pow(1 - c, 3);
};

/**
 * Valeur d'un compteur à un instant donné de l'animation.
 * @returns `end` dès que `duration ≤ 0` ou `elapsed ≥ duration` (état final garanti, jamais de
 *          dépassement), `start` si `elapsed ≤ 0`, sinon l'interpolation adoucie entre les deux.
 */
export const countUpValue = (
  elapsed: number,
  duration: number,
  start: number,
  end: number,
  easing: (t: number) => number = easeOutCubic,
): number => {
  if (duration <= 0 || elapsed >= duration) return end;
  if (elapsed <= 0) return start;
  return start + (end - start) * easing(elapsed / duration);
};

/**
 * Formate un nombre : décimales fixes (arrondi) + séparateur de milliers optionnel.
 * @param groupSeparator séparateur de milliers ('' = aucun, défaut, pour ne pas surprendre
 *                       l'affichage existant qui n'en met pas).
 */
export const formatCount = (value: number, decimals = 0, groupSeparator = ''): string => {
  const safe = Number.isFinite(value) ? value : 0;
  const fixed = Math.abs(safe).toFixed(decimals);
  const [intPart, decPart] = fixed.split('.');
  const grouped = groupSeparator
    ? intPart.replace(/\B(?=(\d{3})+(?!\d))/g, groupSeparator)
    : intPart;
  const sign = safe < 0 ? '-' : '';
  return decPart ? `${sign}${grouped}.${decPart}` : `${sign}${grouped}`;
};

/** Jeu de caractères du brouillage « déchiffrement » (effet DecryptedText). */
export const DECRYPT_CHARS =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!<>-_\\/[]{}=+*^?#';

/**
 * Rend une étape du « déchiffrement » : les `revealed` premiers caractères sont réels, les
 * suivants sont brouillés (via `rng`). Espaces et sauts de ligne sont toujours préservés pour
 * conserver la silhouette du texte. `rng` est injectable pour un test déterministe.
 */
export const scrambleReveal = (
  target: string,
  revealed: number,
  charset: string = DECRYPT_CHARS,
  rng: () => number = Math.random,
): string => {
  let out = '';
  for (let i = 0; i < target.length; i++) {
    const ch = target[i];
    if (i < revealed || ch === ' ' || ch === '\n' || ch === '\t') out += ch;
    else out += charset.charAt(Math.floor(rng() * charset.length));
  }
  return out;
};
