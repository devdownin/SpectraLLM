import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { toast } from 'sonner';
import '../i18n';
import i18n from 'i18next';

// ── Mocks ────────────────────────────────────────────────────────────────────
// Le service API et les toasts sont simulés : le test cible la contre-pression (429)
// et la relance d'une ingestion échouée, pas les appels réseau réels.
const uploadFile = vi.fn();
vi.mock('../services/api', () => ({
  ingestApi: {
    uploadFile: (file: File) => uploadFile(file),
    ingestUrls: vi.fn(() => Promise.resolve({ data: { taskId: 't' } })),
    getTaskStatus: vi.fn(() => Promise.resolve({ data: { status: 'PENDING' } })),
    getAllTasks: vi.fn(() => Promise.resolve({ data: [] })),
    getHistory: vi.fn(() => Promise.resolve({ data: { content: [], totalElements: 0, number: 0 } })),
  },
  datasetApi: {
    getStats: vi.fn(() => Promise.resolve({ data: { totalPairs: 0, chunksInStore: 0, avgConfidence: 0, byCategory: {} } })),
    getGenerationStatus: vi.fn(() => Promise.resolve({ data: {} })),
    getAllTasks: vi.fn(() => Promise.resolve({ data: [] })),
  },
}));

vi.mock('sonner', () => ({
  toast: { warning: vi.fn(), error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

import Ingestion from './Ingestion';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <Ingestion />
    </QueryClientProvider>,
  );
}

function selectFile(container: HTMLElement) {
  const input = container.querySelector('input[type="file"]') as HTMLInputElement;
  const file = new File(['contenu du document'], 'doc.txt', { type: 'text/plain' });
  fireEvent.change(input, { target: { files: [file] } });
}

beforeEach(() => {
  vi.clearAllMocks();
  i18n.changeLanguage('en');
});

describe('Ingestion — backpressure (429) and retry', () => {
  it('shows a "Server busy" toast and a Retry button when the server returns 429', async () => {
    uploadFile.mockRejectedValue({ response: { status: 429, data: { detail: 'too many' } } });
    const { container } = renderPage();

    selectFile(container);

    await waitFor(() => expect(toast.warning).toHaveBeenCalled());
    // Le toast d'erreur générique ne doit PAS être utilisé pour un 429.
    expect(toast.error).not.toHaveBeenCalled();
    // La ligne reste relançable.
    expect(await screen.findByText(/Retry/i)).toBeInTheDocument();
  });

  it('re-submits the original file when Retry is clicked', async () => {
    uploadFile.mockRejectedValue({ response: { status: 429, data: { detail: 'too many' } } });
    const { container } = renderPage();

    selectFile(container);
    const retry = await screen.findByText(/Retry/i);
    expect(uploadFile).toHaveBeenCalledTimes(1);

    fireEvent.click(retry);

    // La relance ré-injecte le même fichier (source conservée en mémoire).
    await waitFor(() => expect(uploadFile).toHaveBeenCalledTimes(2));
    expect(uploadFile.mock.calls[1][0]).toBeInstanceOf(File);
    expect(uploadFile.mock.calls[1][0].name).toBe('doc.txt');
  });

  it('uses the generic error toast (not the busy one) for a non-429 failure', async () => {
    uploadFile.mockRejectedValue({ response: { status: 500, data: { detail: 'boom' } }, message: 'boom' });
    const { container } = renderPage();

    selectFile(container);

    await waitFor(() => expect(toast.error).toHaveBeenCalled());
    expect(toast.warning).not.toHaveBeenCalled();
  });
});
