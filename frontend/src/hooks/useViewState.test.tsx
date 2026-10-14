import { renderHook, act } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { useViewState } from './useViewState';

it('restores filters and sets on a fresh mount, without sharing another screen’s state', () => {
  const first = renderHook(() => useViewState('docs.formats', new Set<string>()));
  act(() => first.result.current[1](new Set(['pdf']))); first.unmount();
  expect(renderHook(() => useViewState('docs.formats', new Set<string>())).result.current[0]).toEqual(new Set(['pdf']));
  expect(renderHook(() => useViewState('jobs.search', '')).result.current[0]).toBe('');
});
it('recovers from corrupt storage and invalid pagination', () => {
  sessionStorage.setItem('spectra.view.docs.search', '{bad');
  sessionStorage.setItem('spectra.view.docs.page', '-10');
  expect(renderHook(() => useViewState('docs.search', '')).result.current[0]).toBe('');
  expect(renderHook(() => useViewState('docs.page', 0, n => Number.isInteger(n) && n >= 0)).result.current[0]).toBe(0);
});
it('remains usable when browser storage is blocked', () => {
  const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
  const view = renderHook(() => useViewState('docs.search', ''));
  act(() => view.result.current[1]('invoice'));
  expect(view.result.current[0]).toBe('invoice'); get.mockRestore(); set.mockRestore();
});
