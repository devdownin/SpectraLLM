/** Concatène des classes conditionnelles (mini clsx, sans dépendance). */
export function cn(...classes: Array<string | false | null | undefined>): string {
  return classes.filter(Boolean).join(' ');
}
