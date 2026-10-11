import { useEffect } from 'react';
import type { RefObject } from 'react';

/** Wait for asynchronous content to regain its height; user scrolling always wins. */
export function useScrollMemory(key: string, ref?: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const element = ref?.current;
    if (ref && !element) return;
    const target = element ?? window;
    const storageKey = `spectra.scroll.${key}`;
    let saved = 0;
    try { saved = Math.max(0, Number(sessionStorage.getItem(storageKey)) || 0); } catch { /* storage unavailable */ }
    let restoring = saved > 0;
    if (!element && !saved) window.scrollTo({ top: 0, behavior: 'instant' });
    const position = () => element ? element.scrollTop : window.scrollY;
    const persist = () => { if (!restoring) try { sessionStorage.setItem(storageKey, String(position())); } catch { /* storage unavailable */ } };
    const restore = () => {
      if (!restoring) return;
      const height = element ? element.scrollHeight - element.clientHeight : document.documentElement.scrollHeight - window.innerHeight;
      if (height >= saved) {
        if (element) element.scrollTop = saved; else window.scrollTo({ top: saved, behavior: 'instant' });
        restoring = false;
      }
    };
    const interrupt = () => { restoring = false; };
    const keyInterrupt = (event: Event) => { if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes((event as KeyboardEvent).key)) interrupt(); };
    const observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(restore) : null;
    observer?.observe(element ?? document.body);
    if (element?.firstElementChild) observer?.observe(element.firstElementChild);
    const mutations = typeof MutationObserver !== 'undefined' ? new MutationObserver(restore) : null;
    mutations?.observe(element ?? document.body, { childList: true, subtree: true });
    target.addEventListener('scroll', persist, { passive: true });
    target.addEventListener('wheel', interrupt, { passive: true });
    target.addEventListener('touchstart', interrupt, { passive: true });
    target.addEventListener('keydown', keyInterrupt);
    restore();
    return () => {
      persist(); observer?.disconnect(); mutations?.disconnect();
      target.removeEventListener('scroll', persist); target.removeEventListener('wheel', interrupt);
      target.removeEventListener('touchstart', interrupt); target.removeEventListener('keydown', keyInterrupt);
    };
  }, [key, ref]);
}
