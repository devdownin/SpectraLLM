import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '../i18n';
import i18n from '../i18n';
import DashboardWorkOverview from './DashboardWorkOverview';
import type { GlobalTask } from '../hooks/useGlobalTasks';

const mocks = vi.hoisted(() => ({ consistency: vi.fn(), reindex: vi.fn(), tasks: [] as GlobalTask[] }));
vi.mock('../services/api', () => ({ configApi: {
  getEmbeddingConsistency: mocks.consistency,
  getReindexStatuses: mocks.reindex,
} }));
vi.mock('../hooks/useGlobalTasks', () => ({ useGlobalTasks: () => ({
  tasks: mocks.tasks,
  activeTasks: mocks.tasks.filter(t => t.status === 'running' || t.status === 'pending'),
  isLoading: false,
  liveStatus: 'open',
}) }));

const service = { available: true, details: { activeModelLoaded: true } };
const defaults = {
  chunks: 10, loading: false, unavailable: false, updatedAt: 1_700_000_000_000,
  chat: service, embedding: service, store: { available: true }, unqualifiedDocuments: 0,
};

function show(overrides: Partial<typeof defaults> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter>
    <DashboardWorkOverview {...defaults} {...overrides}><p>Index controls</p></DashboardWorkOverview>
  </MemoryRouter></QueryClientProvider>);
}

function task(id: string, status: GlobalTask['status'], timestamp: string, error: string | null = null): GlobalTask {
  return { id, kind: 'ingestion', label: id, icon: 'cloud_upload', status, path: '/ingestion',
    detail: null, progress: status === 'running' ? 0.5 : null, timestamp, startedAt: null, error };
}

beforeEach(async () => {
  await i18n.changeLanguage('en');
  mocks.tasks = [];
  mocks.consistency.mockResolvedValue({ data: { mismatches: 0, collections: [{ name: 'docs', status: 'OK' }] } });
  mocks.reindex.mockResolvedValue({ data: [] });
});

describe('Dashboard work overview', () => {
  it('offers a RAG test when models, store and index are verified, without requiring training pairs', async () => {
    show();
    expect(await screen.findByRole('link', { name: 'Test an answer' })).toHaveAttribute('href', '/playground');
    expect(screen.getByText(/Statistics last read:/)).toBeInTheDocument();
  });

  it('offers ingestion for a verified empty knowledge base', async () => {
    show({ chunks: 0 });
    expect(await screen.findByRole('link', { name: 'Import documents' })).toHaveAttribute('href', '/ingestion');
  });

  it.each([
    ['unavailable statistics', { unavailable: true }],
    ['loading statistics', { loading: true }],
  ])('does not offer a test with %s', async (_label, props) => {
    show(props);
    await screen.findByText('Readiness still needs verification');
    expect(screen.queryByRole('link', { name: 'Test an answer' })).not.toBeInTheDocument();
  });

  it('does not claim readiness after a consistency request fails', async () => {
    mocks.consistency.mockRejectedValue(new Error('offline'));
    show();
    expect(await screen.findByText(/Some data is unavailable/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Test an answer' })).not.toBeInTheDocument();
  });

  it('does not claim readiness for an unstamped index', async () => {
    mocks.consistency.mockResolvedValue({ data: { mismatches: 0, collections: [{ status: 'UNSTAMPED' }] } });
    show();
    await screen.findByText('No completed results available.');
    expect(screen.queryByRole('link', { name: 'Test an answer' })).not.toBeInTheDocument();
  });

  it.each(['mismatch', 'reindex'])('directs the user to index controls for %s', async reason => {
    if (reason === 'mismatch') mocks.consistency.mockResolvedValue({ data: { mismatches: 1, collections: [{ status: 'MISMATCH' }] } });
    else mocks.reindex.mockResolvedValue({ data: [{ status: 'RUNNING' }] });
    show();
    expect(await screen.findByRole('link', { name: 'Examine the index' })).toHaveAttribute('href', '#dashboard-attention');
    expect(screen.queryByRole('link', { name: 'Test an answer' })).not.toBeInTheDocument();
  });

  it('does not offer a test while a model is loading', async () => {
    show({ chat: { available: true, details: { activeModelLoaded: false } } });
    expect(await screen.findByRole('link', { name: 'Check models' })).toHaveAttribute('href', '/model-hub');
  });

  it('separates running tasks, failures and recent successful results, including partial failures', async () => {
    mocks.tasks = [
      task('older-result', 'completed', '2026-10-01T12:00:00Z'),
      task('working', 'running', '2026-10-10T12:00:00Z'),
      task('failed-file', 'failed', '2026-10-10T10:00:00Z', 'Read failed'),
      task('partial-file', 'completed', '2026-10-10T11:00:00Z', 'One file failed'),
      task('newer-result', 'completed', '2026-10-09T12:00:00Z'),
    ];
    show({ unqualifiedDocuments: 3 });
    await screen.findByRole('link', { name: 'Test an answer' });
    const running = within(screen.getByRole('region', { name: /In progress/ }));
    expect(running.getByText('working')).toBeInTheDocument();
    expect(running.getByRole('progressbar')).toHaveAttribute('value', '0.5');
    const attention = within(screen.getByRole('region', { name: 'Needs attention' }));
    expect(attention.getByText('failed-file')).toBeInTheDocument();
    expect(attention.getByText('partial-file')).toBeInTheDocument();
    expect(attention.getByRole('link', { name: 'Examine documents' })).toHaveAttribute('href', '/documents');
    const results = within(screen.getByRole('region', { name: 'Latest results' }));
    const links = results.getAllByRole('link');
    expect(links[0]).toHaveTextContent('newer-result');
    expect(links[1]).toHaveTextContent('older-result');
    expect(results.queryByText('partial-file')).not.toBeInTheDocument();
  });
});
