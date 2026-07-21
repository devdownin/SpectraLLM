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
const ingestUrls = vi.fn(() => Promise.resolve({ data: { taskId: 't' } }));
vi.mock('../services/api', () => ({
  ingestApi: {
    uploadFile: (file: File) => uploadFile(file),
    ingestUrls: (urls: string[]) => ingestUrls(urls),
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

/** Saisit une URL dans le champ dédié et valide (Entrée) pour lancer une ingestion URL. */
function submitUrl(url: string) {
  const field = screen.getByPlaceholderText(/https?:\/\//i) as HTMLInputElement;
  fireEvent.change(field, { target: { value: url } });
  fireEvent.keyDown(field, { key: 'Enter' });
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

  it('handles a 429 on URL ingestion and retries the same URL', async () => {
    ingestUrls.mockRejectedValue({ response: { status: 429, data: { detail: 'too many' } } });
    renderPage();

    submitUrl('https://example.com/doc.pdf');

    await waitFor(() => expect(toast.warning).toHaveBeenCalled());
    const retry = await screen.findByText(/Retry/i);
    expect(ingestUrls).toHaveBeenCalledTimes(1);

    fireEvent.click(retry);

    await waitFor(() => expect(ingestUrls).toHaveBeenCalledTimes(2));
    expect(ingestUrls.mock.calls[1][0]).toEqual(['https://example.com/doc.pdf']);
  });
});
