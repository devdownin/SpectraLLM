import ContextBackLink from '../components/ContextBackLink';
import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Comparison from './Comparison';
import { evaluationApi } from '../services/api';
import '../i18n';
import i18n from '../i18n';
vi.mock('../components/charts/ScoreRadar', () => ({ default: () => null }));
vi.mock('../services/api', async original => ({ ...(await original<object>()), evaluationApi: { getAll: vi.fn(), get: vi.fn(), getAllAb: vi.fn(), getAb: vi.fn() }, configApi: { getModels: vi.fn().mockResolvedValue({ data: [] }) } }));
const report = { evalId: 'requested', modelName: 'Target model', status: 'COMPLETED', startedAt: '2026-10-10T12:00:00Z', completedAt: '2026-10-10T12:01:00Z', processed: 1, testSetSize: 1, averageScore: 8, avgLatencyMs: 0, avgTokensPerSec: 0, scoresByCategory: {}, scores: [] };
beforeEach(() => { vi.clearAllMocks(); void i18n.changeLanguage('en'); vi.mocked(evaluationApi.getAll).mockResolvedValue({ data: [] } as never); });
function show(path: string) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={[{ pathname: path.split('?')[0], search: path.includes('?') ? '?' + path.split('?')[1] : '', state: { origin: { path: '/activity', label: 'Activity' } } }]}><ContextBackLink /><Comparison /></MemoryRouter></QueryClientProvider>);
}
it('opens the exact report even when the history list does not contain it and closes with Escape', async () => {
  vi.mocked(evaluationApi.get).mockResolvedValue({ data: report } as never);
  show('/comparison?eval=requested');
  expect(await screen.findByRole('dialog', { name: 'Target model' })).toBeInTheDocument();
  expect(evaluationApi.get).toHaveBeenCalledWith('requested');
  expect(screen.getByRole('region', { name: 'History' })).toHaveTextContent('10/10/26');
  await userEvent.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Back to Activity' })).toBeInTheDocument();
});
it('does not silently select the latest report when the requested report is missing', async () => {
  vi.mocked(evaluationApi.getAll).mockResolvedValue({ data: [{ ...report, evalId: 'other', modelName: 'Other model' }] } as never);
  vi.mocked(evaluationApi.get).mockRejectedValue(new Error('404'));
  show('/comparison?eval=missing');
  expect(await screen.findByRole('alert')).toHaveTextContent('No other result has been selected');
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
});
it('opens the exact A/B report independently from the evaluation history', async () => {
  vi.mocked(evaluationApi.getAllAb).mockResolvedValue({ data: [] } as never);
  vi.mocked(evaluationApi.getAb).mockResolvedValue({ data: { abId: 'target-ab', modelA: 'Alpha', modelB: 'Beta', status: 'COMPLETED', startedAt: report.startedAt, processed: 0, items: [] } } as never);
  show('/comparison?ab=target-ab');
  expect(await screen.findByRole('dialog', { name: 'Alpha vs Beta' })).toBeInTheDocument();
  expect(evaluationApi.getAb).toHaveBeenCalledWith('target-ab');
  await userEvent.keyboard('{Escape}');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByText(i18n.t('ab.newRun'))).toBeInTheDocument();
});
