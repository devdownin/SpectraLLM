import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { toast } from 'sonner';
import '../i18n';
import i18n from 'i18next';
import type { GlobalTask } from '../hooks/useGlobalTasks';

// ── Mocks ────────────────────────────────────────────────────────────────────
// useGlobalTasks est simulé pour injecter une tâche d'ingestion d'URLs ÉCHOUÉE ;
// etaMs / formatEta restent réels (le composant les importe du même module).
const failedUrlTask: GlobalTask = {
  id: 'ingestion:t1',
  kind: 'ingestion',
  icon: 'cloud_upload',
  label: 'https://example.com/a.pdf',
  detail: null,
  status: 'failed',
  progress: null,
  path: '/ingestion',
  error: 'timeout',
  timestamp: '2026-07-21T10:00:00Z',
  startedAt: '2026-07-21T09:59:00Z',
  retryUrls: ['https://example.com/a.pdf'],
};

vi.mock('../hooks/useGlobalTasks', async (importActual) => {
  const actual = await importActual<typeof import('../hooks/useGlobalTasks')>();
  return {
    ...actual,
    useGlobalTasks: () => ({
      tasks: [failedUrlTask],
      activeTasks: [],
      activeCount: 0,
      isLoading: false,
      liveStatus: 'open' as const,
    }),
  };
});

const { ingestUrls, noop } = vi.hoisted(() => ({
  ingestUrls: vi.fn(() => Promise.resolve({ data: { taskId: 't2' } })),
  noop: vi.fn(() => Promise.resolve({ data: {} })),
}));
vi.mock('../services/api', () => ({
  ingestApi: { ingestUrls: (urls: string[]) => ingestUrls(urls), cancelTask: noop },
  datasetApi: { cancelGeneration: noop },
  dpoApi: { cancelTask: noop },
  fineTuningApi: { cancelJob: noop },
  evaluationApi: { cancel: noop, cancelAb: noop },
  modelsHubApi: { cancelInstallation: noop },
  ablationApi: { cancelJob: noop },
}));

vi.mock('sonner', () => ({
  toast: { info: vi.fn(), error: vi.fn(), success: vi.fn(), warning: vi.fn() },
}));

import TaskCenter from './TaskCenter';

function renderCenter() {
  return render(<MemoryRouter><TaskCenter /></MemoryRouter>);
}

beforeEach(() => {
  vi.clearAllMocks();
  i18n.changeLanguage('en');
});

describe('TaskCenter — cross-page URL retry', () => {
  it('re-submits the URLs of a failed URL ingestion when Retry is clicked', async () => {
    renderCenter();

    // Ouvre le panneau (la liste « Recent » ne rend qu'ouvert).
    fireEvent.click(screen.getByLabelText('Background tasks'));

    const retry = await screen.findByLabelText('Retry this URL ingestion');
    fireEvent.click(retry);

    await waitFor(() => expect(ingestUrls).toHaveBeenCalledTimes(1));
    expect(ingestUrls.mock.calls[0][0]).toEqual(['https://example.com/a.pdf']);
    expect(toast.info).toHaveBeenCalled();
  });
});
