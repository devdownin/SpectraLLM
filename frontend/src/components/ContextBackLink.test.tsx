import { beforeEach, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ContextBackLink from './ContextBackLink';
import Activity from '../pages/Activity';
import { vi } from 'vitest';
import '../i18n';
import i18n from '../i18n';
vi.mock('../hooks/useGlobalTasks', () => ({ useGlobalTasks: () => ({ tasks: [], isLoading: false, liveStatus: 'open' }), isActiveTask: () => false }));
beforeEach(() => { void i18n.changeLanguage('en'); });
it('returns to the origin and restores Activity filters', async () => {
  render(<MemoryRouter initialEntries={[{ pathname: '/comparison', state: { origin: { path: '/activity', label: 'Activity', state: { activityFilters: { status: 'failed', kind: 'evaluation', search: 'specific run' } } } } }]}><Routes><Route path="/comparison" element={<ContextBackLink />} /><Route path="/activity" element={<Activity />} /></Routes></MemoryRouter>);
  await userEvent.click(screen.getByRole('link', { name: 'Back to Activity' }));
  expect(screen.getByLabelText('Status')).toHaveValue('failed'); expect(screen.getByLabelText('Type')).toHaveValue('evaluation'); expect(screen.getByLabelText('Search tasks')).toHaveValue('specific run');
});
it('omits a return link on direct navigation', () => { render(<MemoryRouter><ContextBackLink /></MemoryRouter>); expect(screen.queryByRole('link')).not.toBeInTheDocument(); });
