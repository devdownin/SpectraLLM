import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { WorkList, WorkListRow } from './WorkList';
import { ListFeedback } from './ListFeedback';
import '../../i18n';
import i18n from '../../i18n';

beforeEach(async () => { await i18n.changeLanguage('en'); });
function rows() { return Array.from({ length: 25 }, (_, n) => <WorkListRow key={n} identity={`Job ${n}`} status="Completed" context="Model A" updated="Today" actions={<button>Open {n}</button>} />); }
it('restores pagination on remount and resets it when filters change while unmounted', async () => {
  const user = userEvent.setup();
  let view = render(<WorkList viewKey="jobs" filters="all" label="Jobs">{rows()}</WorkList>);
  await user.click(screen.getByRole('button', { name: 'Next' }));
  expect(screen.getByText('Job 20')).toBeVisible(); view.unmount();
  view = render(<WorkList viewKey="jobs" filters="all" label="Jobs">{rows()}</WorkList>);
  expect(screen.getByText('Job 20')).toBeVisible(); view.unmount();
  render(<WorkList viewKey="jobs" filters="failed" label="Jobs">{rows()}</WorkList>);
  expect(screen.getByText('Job 0')).toBeVisible(); expect(screen.queryByText('Job 20')).not.toBeInTheDocument();
});
it('keeps known items visible during a refresh failure and offers retry', async () => {
  const retry = vi.fn();
  render(<ListFeedback error retry={retry}><p>Known job</p></ListFeedback>);
  expect(within(screen.getByRole('alert')).getByText('Known job')).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: 'Retry' })); expect(retry).toHaveBeenCalledOnce();
});
it('distinguishes loading, empty and failed lists', () => {
  const view = render(<ListFeedback loading><p>stale empty content</p></ListFeedback>);
  expect(screen.getByRole('status')).toHaveAccessibleName('Loading list…'); expect(screen.queryByText('stale empty content')).not.toBeInTheDocument();
  view.rerender(<ListFeedback empty action={<button>New job</button>}><span /></ListFeedback>);
  expect(screen.getByRole('button', { name: 'New job' })).toBeVisible();
  view.rerender(<ListFeedback error retry={() => {}}><span /></ListFeedback>);
  expect(screen.queryByText('No items to display')).not.toBeInTheDocument(); expect(screen.getByRole('alert')).toBeVisible();
});
