import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { useFocusTrap } from './useFocusTrap';

/**
 * A minimal dialog wired to useFocusTrap, used to exercise the trap in isolation.
 * `onEscape` is passed straight through so tests can assert Escape handling and
 * — critically — that passing a *new* inline callback on re-render does NOT
 * tear the trap down and steal focus (the bug this hook was rewritten to fix).
 */
function Dialog({ active, onEscape }: { active: boolean; onEscape?: () => void }) {
  const ref = useFocusTrap<HTMLDivElement>(active, onEscape);
  return (
    <div ref={ref} tabIndex={-1} data-testid="dialog">
      <button data-testid="first">First</button>
      <input data-testid="middle" />
      <button data-testid="last">Last</button>
    </div>
  );
}

describe('useFocusTrap', () => {
  it('moves focus into the container when activated', () => {
    render(<Dialog active />);
    expect(screen.getByTestId('first')).toHaveFocus();
  });

  it('calls onEscape when Escape is pressed', () => {
    const onEscape = vi.fn();
    render(<Dialog active onEscape={onEscape} />);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onEscape).toHaveBeenCalledTimes(1);
  });

  it('does not trap or fire onEscape when inactive', () => {
    const onEscape = vi.fn();
    render(<Dialog active={false} onEscape={onEscape} />);
    // Nothing inside the dialog should have grabbed focus.
    expect(screen.getByTestId('first')).not.toHaveFocus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('wraps focus forward: Tab on the last element returns to the first', () => {
    render(<Dialog active />);
    const last = screen.getByTestId('last');
    last.focus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(screen.getByTestId('first')).toHaveFocus();
  });

  it('wraps focus backward: Shift+Tab on the first element returns to the last', () => {
    render(<Dialog active />);
    // First element already focused on activation.
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(screen.getByTestId('last')).toHaveFocus();
  });

  it('restores focus to the previously focused element on deactivation', () => {
    // An element outside the dialog holds focus before the trap opens.
    render(
      <>
        <button data-testid="opener">Open</button>
        <Wrapper />
      </>,
    );
    const opener = screen.getByTestId('opener');
    opener.focus();
    expect(opener).toHaveFocus();

    // Activate the trap, then deactivate it.
    fireEvent.click(screen.getByTestId('toggle'));
    expect(screen.getByTestId('first')).toHaveFocus();
    fireEvent.click(screen.getByTestId('toggle'));
    expect(opener).toHaveFocus();
  });

  it('does NOT steal focus when re-rendered with a new inline onEscape callback', () => {
    // Regression guard: the trap keeps the callback in a ref, so a parent
    // re-render passing a fresh arrow must not re-run the effect (which would
    // yank focus back to the first element mid-typing).
    const { rerender } = render(<Dialog active onEscape={() => {}} />);

    // Simulate the user tabbing to the middle input.
    const middle = screen.getByTestId('middle');
    middle.focus();
    expect(middle).toHaveFocus();

    // Parent re-renders with a brand-new callback identity.
    rerender(<Dialog active onEscape={() => {}} />);

    // Focus must stay where the user put it.
    expect(middle).toHaveFocus();
  });
});

/** Helper component with its own toggle so a test can open/close the trap. */
function Wrapper() {
  const [active, setActive] = useState(false);
  return (
    <>
      <button data-testid="toggle" onClick={() => act(() => setActive(a => !a))}>
        Toggle
      </button>
      <Dialog active={active} />
    </>
  );
}
