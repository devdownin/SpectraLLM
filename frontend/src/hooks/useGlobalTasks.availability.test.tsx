import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { beforeEach, expect, it, vi } from 'vitest';
import { useGlobalTasks } from './useGlobalTasks';
const calls = vi.hoisted(() => ({ ingest: vi.fn(), other: vi.fn() }));
vi.mock('./useSse', () => ({ useSse: () => ({ data: null, status: 'closed' }) }));
vi.mock('../services/api', () => ({
  ingestApi: { getAllTasks: calls.ingest }, datasetApi: { getAllTasks: calls.other }, dpoApi: { getAllTasks: calls.other },
  fineTuningApi: { getJobs: calls.other }, evaluationApi: { getAll: calls.other, getAllAb: calls.other },
  modelsHubApi: { getInstallations: calls.other }, qualityBenchmarkApi: { listCompareJobs: calls.other }, ablationApi: { listJobs: calls.other },
}));
function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return renderHook(() => useGlobalTasks(), { wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> });
}
beforeEach(() => { calls.ingest.mockReset(); calls.other.mockReset(); });
it('reports complete unavailability instead of a successful empty list', async () => {
  calls.ingest.mockRejectedValue(new Error('offline')); calls.other.mockRejectedValue(new Error('offline'));
  const view = show(); await waitFor(() => expect(view.result.current.isError).toBe(true));
  expect(view.result.current.isLoading).toBe(false);
});
it('keeps available tasks and discloses incomplete sources', async () => {
  calls.ingest.mockResolvedValue({ data: [{ taskId: 'known', status: 'COMPLETED', files: ['known.pdf'] }] }); calls.other.mockRejectedValue(new Error('offline'));
  const view = show(); await waitFor(() => expect(view.result.current.tasks).toHaveLength(1));
  expect(view.result.current.unavailable).toBe(8); expect(view.result.current.isError).toBe(false);
});
it('preserves the last good list if a later refresh fails, then recovers on retry', async () => {
  calls.ingest.mockResolvedValue({ data: [{ taskId: 'known', status: 'COMPLETED', files: ['known.pdf'] }] }); calls.other.mockResolvedValue({ data: [] });
  const view = show(); await waitFor(() => expect(view.result.current.tasks).toHaveLength(1));
  calls.ingest.mockRejectedValue(new Error('offline')); calls.other.mockRejectedValue(new Error('offline'));
  await act(async () => { await view.result.current.refetch(); });
  await waitFor(() => expect(view.result.current.isError).toBe(true)); expect(view.result.current.tasks).toHaveLength(1);
  calls.ingest.mockResolvedValue({ data: [] }); calls.other.mockResolvedValue({ data: [] });
  await act(async () => { await view.result.current.refetch(); });
  await waitFor(() => expect(view.result.current.isError).toBe(false)); expect(view.result.current.tasks).toHaveLength(0);
});
