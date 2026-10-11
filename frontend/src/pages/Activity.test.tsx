import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import '../i18n';
import i18n from '../i18n';
import Activity from './Activity';
import type { GlobalTask } from '../hooks/useGlobalTasks';
const state = vi.hoisted(() => ({ tasks: [] as GlobalTask[], loading: false, live: 'open' }));
vi.mock('../hooks/useGlobalTasks', async importOriginal => ({ ...(await importOriginal<object>()), useGlobalTasks: () => ({ tasks: state.tasks, isLoading: state.loading, liveStatus: state.live }) }));
beforeEach(async () => {
 await i18n.changeLanguage('en'); state.loading = false; state.live = 'open';
 state.tasks = ['running', 'completed', 'failed', 'partial', 'cancelled'].map((status, i) => ({ id: `ingestion:${i}`, kind: 'ingestion', icon: 'cloud_upload', label: `${status}.pdf`, status, detail: '5 chunks', progress: status === 'running' ? 0.5 : null, path: '/ingestion', error: status === 'partial' || status === 'failed' ? 'PDF extraction failed' : null, timestamp: '2026-10-10T12:00:00Z', startedAt: null })) as GlobalTask[];
});
it('shows progress, errors, distinct outcomes and result links', () => {
 render(<MemoryRouter><Activity /></MemoryRouter>);
 expect(screen.getByRole('progressbar')).toHaveAttribute('value','0.5');
 expect(screen.getByText('Partial success', {selector:'span'})).toBeInTheDocument();
 expect(screen.getByText('Cancelled', {selector:'span'})).toBeInTheDocument();
 const partial = within(screen.getByText('partial.pdf').closest('li')!);
 expect(partial.getByText('PDF extraction failed')).toBeVisible();
 expect(partial.getByRole('link', { name: 'Open results' })).toHaveAttribute('href','/ingestion');
 expect(screen.getByText('running.pdf').closest('li')).toBe(screen.getAllByRole('listitem')[0]);
});
it('filters by status, family and search, then exposes empty results', async () => {
 const user = userEvent.setup(); render(<MemoryRouter><Activity /></MemoryRouter>);
 await user.selectOptions(screen.getByLabelText('Status'), 'cancelled');
 expect(screen.getByText('cancelled.pdf')).toBeInTheDocument();
 expect(screen.queryByText('failed.pdf')).not.toBeInTheDocument();
 await user.selectOptions(screen.getByLabelText('Type'), 'evaluation');
 expect(screen.getByText('No tasks match these filters.')).toBeInTheDocument();
 await user.selectOptions(screen.getByLabelText('Type'), 'all');
 await user.selectOptions(screen.getByLabelText('Status'), 'all');
 await user.type(screen.getByLabelText('Search tasks'), 'extraction');
 expect(screen.getAllByRole('listitem')).toHaveLength(2);
});
it('shows fallback, loading and indeterminate progress without inventing a percentage', () => {
 state.live = 'closed'; state.tasks = [{ ...state.tasks[0], progress: null }];
 const view = render(<MemoryRouter><Activity /></MemoryRouter>);
 expect(screen.getByText(/Some sources may be unavailable/)).toBeInTheDocument();
 expect(screen.getByText('Progress unavailable.')).toBeInTheDocument();
 view.unmount(); state.loading = true;
 render(<MemoryRouter><Activity /></MemoryRouter>);
 expect(screen.getByText('Loading tasks…')).toBeInTheDocument();
});
