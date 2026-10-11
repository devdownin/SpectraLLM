import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Optimization from './Optimization';
import { ablationApi } from '../services/api';
import '../i18n';
import i18n from '../i18n';
vi.mock('../components/charts/AblationCharts', () => ({ default: () => <p>Charts</p> }));
vi.mock('../services/api', () => ({ fineTuningApi: { getModels: vi.fn().mockResolvedValue({ data: [] }) }, ablationApi: { listJobs: vi.fn(), getJob: vi.fn() } }));
const time = '2026-10-10T12:00:00Z';
const report = { benchmarkSize: 1, startedAt: time, completedAt: time, arms: [{ label: 'Exact arm', model: 'Target model', useRag: true, quality: { avgScore: 8, hallucinationRate: 0, refusalAccuracy: 1, scoresByCategory: {} }, retrieval: { evaluatedQuestions: 1, hitRate: 1, mrr: 1, recallAtK: 1 }, avgLatencyMs: 100, p50LatencyMs: 100, avgContextTokens: 100, runs: 1, stdDev: {}, appliedCounts: {} }] };
const job = { jobId: 'exact', label: 'Exact experiment', status: 'COMPLETED', report, createdAt: time, completedAt: time };
beforeEach(() => { sessionStorage.clear(); vi.mocked(ablationApi.listJobs).mockResolvedValue({ data: [] } as never); vi.clearAllMocks(); void i18n.changeLanguage('en'); vi.mocked(ablationApi.getJob).mockResolvedValue({ data: job } as never); });
function show(path: string) { render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={[path]}><Optimization /></MemoryRouter></QueryClientProvider>); }
it('opens the exact ablation result in the shared panel and can close/reopen it', async () => {
  show('/optimization?job=exact');
  const dialog = await screen.findByRole('dialog'); expect(dialog).toHaveTextContent('Exact arm'); expect(dialog).toHaveTextContent('Charts'); expect(ablationApi.getJob).toHaveBeenCalledWith('exact'); expect(ablationApi.listJobs).toHaveBeenCalled();
  expect(screen.getByRole('region', { name: 'History' })).toHaveTextContent('2026');
  await userEvent.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Open results' })); expect(screen.getByRole('dialog')).toBeInTheDocument();
});
it('reports a missing target without restoring another experiment', async () => {
  vi.mocked(ablationApi.getJob).mockRejectedValue(new Error('404')); show('/optimization?job=missing');
  expect(await screen.findByText(/No other result has been selected/)).toHaveTextContent('No other result has been selected'); expect(ablationApi.listJobs).toHaveBeenCalled(); expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
it('keeps a restored historical result available without opening a panel automatically', async () => {
  vi.mocked(ablationApi.listJobs).mockResolvedValue({ data: [job] } as never); show('/optimization');
  const open = await screen.findByRole('button', { name: 'Open results' }); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); await userEvent.click(open); expect(screen.getByRole('dialog')).toBeInTheDocument();
});
