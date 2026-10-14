import { useSyncExternalStore } from 'react';

const KEY = 'spectra_workspace_collection';
const EVENT = 'spectra:collection';
const subscribe = (callback: () => void) => {
  window.addEventListener(EVENT, callback);
  window.addEventListener('storage', callback);
  return () => { window.removeEventListener(EVENT, callback); window.removeEventListener('storage', callback); };
};
const snapshot = () => { try { return localStorage.getItem(KEY) ?? ''; } catch { return ''; } };
export function useWorkspaceCollection() {
  const collection = useSyncExternalStore(subscribe, snapshot, () => '');
  const setCollection = (value: string) => {
    try { localStorage.setItem(KEY, value); } catch { return; }
    window.dispatchEvent(new Event(EVENT));
  };
  return [collection, setCollection] as const;
}
