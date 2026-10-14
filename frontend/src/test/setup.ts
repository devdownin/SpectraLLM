import '@testing-library/jest-dom';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// jsdom has no layout engine, so `offsetParent` is always null — which makes any
// "is this element visible?" check (e.g. useFocusTrap's focusable filter) drop
// every element. Report an attached element as visible by returning its parent,
// matching how browsers behave for non-fixed, displayed nodes.
Object.defineProperty(HTMLElement.prototype, 'offsetParent', {
  configurable: true,
  get() {
    return this.parentNode ?? null;
  },
});

// Unmount React trees between tests so DOM state (and focus) never leaks across cases.
afterEach(() => {
  cleanup();
});
