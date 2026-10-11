import ContextBackLink from './ContextBackLink';
import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import TaskDetailDestination from './TaskDetailDestination';
import { ingestApi, datasetApi, dpoApi, modelsHubApi, qualityBenchmarkApi } from '../services/api';
import '../i18n';
import i18n from '../i18n';
vi.mock('../services/api', () => ({ ingestApi: { getTaskStatus: vi.fn() }, datasetApi: { getGenerationStatus: vi.fn() }, dpoApi: { getTask: vi.fn() }, modelsHubApi: { getInstallations: vi.fn() }, qualityBenchmarkApi: { getCompareJob: vi.fn() } }));
beforeEach(() => { vi.clearAllMocks(); void i18n.changeLanguage('en'); });
function Location() { return <p data-testid="location">{useLocation().search}</p>; }
function show(target: string) { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={[{ pathname: '/ingestion', search: `?keep=yes&task=${encodeURIComponent(target)}`, state: { origin: { path: '/activity', label: 'Activity' } } }]}><ContextBackLink /><TaskDetailDestination /><Location /></MemoryRouter></QueryClientProvider>); }
it.each([
  ['ingestion', ingestApi.getTaskStatus], ['dataset', datasetApi.getGenerationStatus], ['dpo', dpoApi.getTask], ['benchmark', qualityBenchmarkApi.getCompareJob],
])('fetches the exact %s task and exposes its outcome/history', async (kind, reader) => {
  vi.mocked(reader).mockResolvedValue({ data: { status: 'COMPLETED', createdAt: '2026-10-10T12:00:00Z', completedAt: '2026-10-10T12:01:00Z', pairsGenerated: 7, files: ['exact.pdf'], currentStep: 'Done', outputPath: '/data/result.json' } } as never);
  show(`${kind}:exact`);
  expect(await screen.findByText('Done')).toBeInTheDocument(); expect(reader).toHaveBeenCalledWith('exact');
  expect(screen.getByRole('region', { name: 'History' })).toHaveTextContent('2026');
  await userEvent.keyboard('{Escape}'); expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(screen.getByTestId('location')).toHaveTextContent('?keep=yes'); expect(screen.getByRole('link', { name: 'Back to Activity' })).toBeInTheDocument();
});
it('finds the exact installation and refuses an unavailable identifier', async () => {
  vi.mocked(modelsHubApi.getInstallations).mockResolvedValue({ data: [{ jobId: 'other', modelName: 'Other' }, { jobId: 'wanted', status: 'FAILED', modelName: 'Wanted', error: 'Download failed' }] } as never);
  const view = show('install:wanted'); expect(await screen.findByText('Wanted')).toBeInTheDocument(); expect(screen.getByText('Download failed')).toBeInTheDocument(); view.unmount();
  show('install:missing'); expect(await screen.findByRole('alert')).toHaveTextContent('No other result has been selected'); expect(screen.queryByText('Other')).not.toBeInTheDocument();
});
it('shows unavailable targets without inventing an outcome', async () => {
  vi.mocked(ingestApi.getTaskStatus).mockRejectedValue(new Error('404'));
  show('ingestion:missing'); expect(await screen.findByRole('alert')).toHaveTextContent('unavailable'); expect(screen.queryByText('Completed')).not.toBeInTheDocument();
});

it('keeps partial ingestion outcomes visible and presents both benchmark results', async () => {
  vi.mocked(ingestApi.getTaskStatus).mockResolvedValue({ data: { status: 'COMPLETED', fileErrors: ['One file failed'] } } as never);
  const view = show('ingestion:partial'); expect(await screen.findByText('Partial success')).toBeInTheDocument(); view.unmount();
  vi.mocked(qualityBenchmarkApi.getCompareJob).mockResolvedValue({ data: { status: 'COMPLETED', baselineReport: { model: 'Base', avgScore: 6, items: [] }, candidateReport: { model: 'Candidate', avgScore: 8, items: [{ question: 'Question?', modelAnswer: 'Answer', score: 8 }] } } } as never);
  show('benchmark:result'); expect(await screen.findByText('Candidate')).toBeInTheDocument(); expect(screen.getByText('Base')).toBeInTheDocument(); expect(screen.getByText('Answer')).toBeInTheDocument();
});
