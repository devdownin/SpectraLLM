import { beforeEach, expect, it, vi } from 'vitest';
import { useState } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DetailPanel } from './DetailPanel';
import ConfirmDialog from '../ConfirmDialog';
import '../../i18n';
import i18n from '../../i18n';
beforeEach(() => { void i18n.changeLanguage('en'); HTMLElement.prototype.scrollIntoView = vi.fn(); });
function Example() {
  const [open, setOpen] = useState(false);
  const [confirm, setConfirm] = useState(false);
  return <><button onClick={() => setOpen(true)}>Open report</button>{open && <DetailPanel title="Run 42" onClose={() => setOpen(false)} summary="Outcome" details="Scores" history="Timeline" actions={<button onClick={() => setConfirm(true)}>Delete</button>} />}
    <ConfirmDialog open={confirm} title="Confirm delete" message="Really?" confirmLabel="Delete" onConfirm={() => setConfirm(false)} onCancel={() => setConfirm(false)} /></>;
}
it('has the same sections, moves to a section, traps focus and restores it after Escape', async () => {
  const user = userEvent.setup(); render(<Example />);
  const trigger = screen.getByRole('button', { name: 'Open report' });
  await user.click(trigger);
  const dialog = screen.getByRole('dialog', { name: 'Run 42' });
  const close = within(dialog).getByRole('button', { name: 'Close detail panel' });
  expect(close).toHaveFocus(); expect(document.body.style.overflow).toBe('hidden');
  await user.tab({ shift: true }); expect(within(dialog).getByRole('button', { name: 'Delete' })).toHaveFocus();
  const history = within(dialog).getByRole('button', { name: 'History' });
  await user.click(history); expect(screen.getByRole('region', { name: 'History' })).toHaveFocus();
  history.focus(); await user.keyboard('{ArrowRight}'); expect(within(dialog).getByRole('button', { name: 'Actions' })).toHaveFocus();
  await user.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(trigger).toHaveFocus(); expect(document.body.style.overflow).toBe('');
});
it('closes only the top confirmation before closing its parent panel', async () => {
  const user = userEvent.setup(); render(<Example />); await user.click(screen.getByText('Open report')); await user.click(screen.getByText('Delete'));
  await user.keyboard('{Escape}'); expect(screen.queryByRole('dialog', { name: 'Confirm delete' })).not.toBeInTheDocument(); expect(screen.getByRole('dialog', { name: 'Run 42' })).toBeInTheDocument();
  await user.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
it('does not close on a click inside the panel, but closes on the backdrop', async () => {
  const user = userEvent.setup(); render(<Example />); await user.click(screen.getByText('Open report'));
  await user.click(screen.getByText('Scores')); expect(screen.getByRole('dialog')).toBeInTheDocument();
  await user.click(screen.getByRole('dialog').parentElement!); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
