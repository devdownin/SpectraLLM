import { useEffect, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';

/** Session preferences only: never persist records, selections for bulk actions or forms. */
export function useViewState<T>(key: string, initial: T, valid?: (value: T) => boolean): [T, Dispatch<SetStateAction<T>>] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = sessionStorage.getItem(`spectra.view.${key}`);
      if (raw === null) return initial;
      const parsed = JSON.parse(raw);
      const candidate = initial instanceof Set ? new Set(Array.isArray(parsed) ? parsed : []) : parsed;
      const sameType = initial instanceof Set ? Array.isArray(parsed) && parsed.every(v => typeof v === 'string')
        : Array.isArray(initial) ? Array.isArray(parsed) : typeof candidate === typeof initial;
      return sameType && (!valid || valid(candidate)) ? candidate : initial;
    } catch { return initial; }
  });
  useEffect(() => {
    try { sessionStorage.setItem(`spectra.view.${key}`, JSON.stringify(value instanceof Set ? [...value] : value)); }
    catch { /* Private mode or quota: the view remains usable without persistence. */ }
  }, [key, value]);
  return [value, setValue];
}
