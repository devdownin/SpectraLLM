import { useCallback } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';

/** Updating a selection must not discard the screen of origin from history state. */
export function useContextSearchParams() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const update = useCallback((next: Parameters<typeof setParams>[0], options?: Parameters<typeof setParams>[1]) => {
    setParams(next, { state: location.state, ...options });
  }, [setParams, location.state]);
  return [params, update] as const;
}
