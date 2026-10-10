import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import i18n from '../i18n';
import Dashboard from './Dashboard';

const mocks = vi.hoisted(() => ({
  stats: vi.fn(), documents: vi.fn(), metrics: vi.fn(), status: vi.fn(),
}));
vi.mock('../hooks/useStatus', () => ({ useStatus: mocks.status }));
vi.mock('../hooks/useGlobalTasks', () => ({ useGlobalTasks: () => ({
  tasks: [], activeTasks: [], isLoading: false, liveStatus: 'open',
}) }));
vi.mock('../services/api', () => ({
  datasetApi: { getStats: mocks.stats },
  gedApi: { getStats: mocks.documents, listDocuments: async () => ({ data: { content: [] } }) },
  commentApi: { list: async () => ({ data: [] }) },
  metricsApi: { getPersonalization: mocks.metrics },
  configApi: {
    getEmbeddingConsistency: async () => ({ data: { mismatches: 0, collections: [{ name: 'docs', status: 'OK' }] } }),
    getReindexStatuses: async () => ({ data: [] }),
  },
}));

function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter><Dashboard /></MemoryRouter></QueryClientProvider>);
}

beforeEach(async () => {
  await i18n.changeLanguage('en');
  window.matchMedia = vi.fn().mockReturnValue({ matches: true });
  mocks.stats.mockResolvedValue({ data: { chunksInStore: 30, totalPairs: 0, avgConfidence: 0.9, byCategory: {} } });
  mocks.documents.mockResolvedValue({ data: { byLifecycle: { INGESTED: 2, QUALIFIED: 3 } } });
  mocks.metrics.mockResolvedValue({ data: null });
  mocks.status.mockReturnValue({ loading: false, error: null, status: {
    application: 'Spectra', version: '0.9', timestamp: '2026-10-10T12:00:00Z',
    services: ['llama-cpp', 'llama-cpp-embed', 'chromadb'].map(name => ({
      name, available: true, details: { activeModelLoaded: true, activeModel: 'test-model' },
    })),
  } });
});

describe('Dashboard integration', () => {
  it('shows work first and keeps analytics and technical details in separate collapsed disclosures', async () => {
    show();
    await screen.findByRole('link', { name: 'Test an answer' });
    expect(screen.getByText('2 documents to qualify')).toBeInTheDocument();
    const analytics = screen.getByText('Detailed data and analytics').closest('details')!;
    const technical = screen.getByText('Technical status').closest('details')!;
    expect(analytics).not.toHaveAttribute('open');
    expect(technical).not.toHaveAttribute('open');
    const summary = screen.getByRole('region', { name: 'Your knowledge base is ready to query' });
    expect(summary.compareDocumentPosition(analytics) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(analytics.compareDocumentPosition(technical) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    fireEvent.click(screen.getByText('Detailed data and analytics'));
    expect(analytics).toHaveAttribute('open');
    expect(screen.getByText('Documents & Annotations')).toBeVisible();
    fireEvent.click(screen.getByText('Technical status'));
    expect(technical).toHaveAttribute('open');
    expect(screen.getByText('LLM Inference · llama.cpp')).toBeVisible();
    expect(screen.getByText('Embeddings · llama.cpp')).toBeVisible();
    expect(screen.getByText('Spectra')).toBeVisible();
    fireEvent.click(screen.getByText('Technical status'));
    expect(technical).not.toHaveAttribute('open');
  });

  it('keeps onboarding reachable when the base is empty', async () => {
    mocks.stats.mockResolvedValue({ data: { chunksInStore: 0, totalPairs: 0, byCategory: {} } });
    show();
    await screen.findByRole('link', { name: 'Import documents' });
    fireEvent.click(screen.getByText('Detailed data and analytics'));
    expect(screen.getByText(i18n.t('dashboard.gettingStarted'))).toBeVisible();
    expect(screen.getByText(i18n.t('dashboard.step1Title'))).toBeVisible();
  });

  it('reports partial statistics rather than claiming readiness after an API failure', async () => {
    mocks.stats.mockRejectedValue(new Error('offline'));
    show();
    await waitFor(() => expect(screen.getAllByText(/Some data is unavailable/).length).toBeGreaterThan(0));
    expect(screen.getByRole('heading', { name: 'Readiness still needs verification' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Test an answer' })).not.toBeInTheDocument();
  });
});
