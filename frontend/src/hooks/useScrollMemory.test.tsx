import { act, renderHook, waitFor } from '@testing-library/react';
import { expect, it } from 'vitest';
import { useScrollMemory } from './useScrollMemory';

it('waits for content before restoring a list’s scroll and preserves its position on unmount', async () => {
  const element = document.createElement('div');
  let height = 100;
  Object.defineProperty(element, 'scrollHeight', { get: () => height });
  Object.defineProperty(element, 'clientHeight', { value: 100 });
  const ref = { current: element };
  sessionStorage.setItem('spectra.scroll.docs', '300');
  const view = renderHook(() => useScrollMemory('docs', ref));
  expect(element.scrollTop).toBe(0);
  act(() => { height = 1000; element.appendChild(document.createElement('p')); });
  await waitFor(() => expect(element.scrollTop).toBe(300));
  element.scrollTop = 450; element.dispatchEvent(new Event('scroll')); view.unmount();
  expect(sessionStorage.getItem('spectra.scroll.docs')).toBe('450');
});
it('never overrides a user’s scroll while content is still arriving', async () => {
  const element = document.createElement('div');
  let height = 100;
  Object.defineProperty(element, 'scrollHeight', { get: () => height });
  Object.defineProperty(element, 'clientHeight', { value: 100 });
  sessionStorage.setItem('spectra.scroll.docs', '300');
  renderHook(() => useScrollMemory('docs', { current: element }));
  element.dispatchEvent(new Event('wheel')); element.scrollTop = 50;
  act(() => { height = 1000; element.appendChild(document.createElement('p')); });
  await waitFor(() => expect(element.scrollTop).toBe(50));
});
