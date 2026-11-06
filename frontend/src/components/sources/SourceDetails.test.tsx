import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../../i18n';
import { SourceDetails } from './SourceDetails';
import type { Source, SourceDocument, SourceRun } from '../../types/sources';
const api = vi.hoisted(() => ({ documents: vi.fn(), runs: vi.fn(), document: vi.fn(), preview: vi.fn() }));
vi.mock('../../services/sources', () => ({ sourcesApi: api }));
const source: Source = {
  id: 'source-7', name: 'Engineering docs', type: 'WEB', collection: 'spectra_documents', enabled: true,
  intervalMinutes: 60, config: { url: 'https://docs.example.com/', extensions: ['md', 'pdf'], excludePatterns: ['**/archive/**'], maxDocuments: 1000, maxDepth: 2, maxFileSizeMb: 25 },
  status: 'SUCCESS', documentCount: 26, lastSuccessAt: '2026-11-06T10:00:00Z', nextSyncAt: '2026-11-06T11:00:00Z', lastError: null, lastRun: null,
};
const document: SourceDocument = { id: 'doc-26', externalId: 'api-guide', title: 'API guide', sourceUrl: 'https://docs.example.com/api', revision: 'r1', indexedAt: '2026-11-06T10:00:00Z', lastSeenAt: '2026-11-06T10:00:00Z', status: 'INDEXED', error: null };
const run: SourceRun = { id: 'run-1', sourceId: source.id, status: 'PARTIAL', scanned: 26, indexed: 2, unchanged: 23, deleted: 0, failed: 1, startedAt: '2026-11-06T10:00:00Z', completedAt: '2026-11-06T10:00:04Z', error: 'One page could not be read; stored content was kept.' };
function show(initial = source, selectedDocumentId?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const content = (value: Source) => <QueryClientProvider client={client}><MemoryRouter><SourceDetails source={value} selectedDocumentId={selectedDocumentId} onClose={vi.fn()} onAction={vi.fn()} busy={false} /></MemoryRouter></QueryClientProvider>;
  const result = render(content(initial));
  return { rerenderSource: (value: Source) => result.rerender(content(value)) };
}
beforeEach(async () => {
  vi.resetAllMocks(); await i18n.changeLanguage('en');
  api.documents.mockResolvedValue({ data: { content: [], number: 0, totalPages: 0, totalElements: 0 } });
  api.runs.mockResolvedValue({ data: [] });
  api.document.mockResolvedValue({ data: document });
  api.preview.mockResolvedValue({ data: { chunks: [], truncated: false } });
});
describe('Source document investigation', () => {
  it('pages through documents, opens a source link, and retries a failed published preview', async () => {
    const first = { ...document, id: 'doc-1', title: 'Overview', sourceUrl: null };
    api.documents.mockImplementation((_id: string, page: number) => Promise.resolve({ data: { content: page === 0 ? [first] : [document], number: page, totalPages: 2, totalElements: 26 } }));
    api.preview.mockRejectedValueOnce(new Error('Vector store unavailable')).mockResolvedValueOnce({ data: { chunks: ['Published API version 2'], truncated: false } });
    const user = userEvent.setup(); show();
    expect(await screen.findByRole('button', { name: 'Overview' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'API guide' }));
    expect(api.documents).toHaveBeenLastCalledWith(source.id, 1);
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    const previewError = await screen.findByText('Could not load the published text.');
    await user.click(within(previewError.closest('[role="alert"]') as HTMLElement).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Published API version 2')).toBeInTheDocument();
    expect(api.preview).toHaveBeenCalledTimes(2);
    const original = screen.getAllByRole('link', { name: 'https://docs.example.com/api' })[0];
    expect(original).toHaveAttribute('href', 'https://docs.example.com/api');
    expect(original).toHaveAttribute('rel', 'noopener noreferrer');
    await user.click(screen.getByRole('button', { name: 'Previous' }));
    expect(await screen.findByRole('button', { name: 'Overview' })).toBeInTheDocument();
  });

  it('recovers independently from document-list and history failures without presenting them as empty', async () => {
    api.documents.mockRejectedValueOnce(new Error('Document database unavailable')).mockResolvedValueOnce({ data: { content: [document], number: 0, totalPages: 1, totalElements: 1 } });
    api.runs.mockRejectedValueOnce(new Error('History unavailable')).mockResolvedValueOnce({ data: [run] });
    const user = userEvent.setup(); show();
    const documentFailure = await screen.findByText('Could not load the documents.');
    const historyFailure = await screen.findByText('Could not load the synchronization history.');
    expect(screen.queryByText('No indexed documents yet')).not.toBeInTheDocument();
    expect(screen.queryByText('No synchronization yet')).not.toBeInTheDocument();
    await user.click(within(documentFailure.closest('[role="alert"]') as HTMLElement).getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('button', { name: 'API guide' })).toBeInTheDocument();
    await user.click(within(historyFailure.closest('[role="alert"]') as HTMLElement).getByRole('button', { name: 'Try again' }));
    const report = await screen.findByRole('article');
    expect(report).toHaveTextContent('Partial sync');
    expect(report).toHaveTextContent('One page could not be read; stored content was kept.');
    expect(report).toHaveTextContent('4 s');
    expect(within(report).getByText('Scanned').nextElementSibling).toHaveTextContent('26');
    expect(within(report).getByText('Failed').nextElementSibling).toHaveTextContent('1');
  });

  it('distinguishes a readable document without indexed text from a removed citation', async () => {
    const { rerenderSource } = show(source, document.id);
    expect(await screen.findByText('No published text is available for this document.')).toBeInTheDocument();
    expect(screen.queryByText('Could not load the published text.')).not.toBeInTheDocument();
    api.document.mockRejectedValue(new Error('Document no longer exists'));
    rerenderSource({ ...source, lastRun: run, status: 'PARTIAL' });
    expect(await screen.findByText('This document is unavailable or has been removed from the source.')).toBeInTheDocument();
    expect(screen.queryByText('No published text is available for this document.')).not.toBeInTheDocument();
  });

  it('refreshes the published preview after a partial run even though last full success is unchanged', async () => {
    api.preview.mockResolvedValueOnce({ data: { chunks: ['Previously published text'], truncated: false } }).mockResolvedValueOnce({ data: { chunks: ['Newly published text from the partial run'], truncated: false } });
    const { rerenderSource } = show(source, document.id);
    await screen.findByText('Previously published text');
    rerenderSource({ ...source, status: 'PARTIAL', lastRun: run, lastError: run.error });
    expect(await screen.findByText('Newly published text from the partial run')).toBeInTheDocument();
    expect(screen.queryByText('Previously published text')).not.toBeInTheDocument();
    await waitFor(() => expect(api.documents).toHaveBeenCalledTimes(2));
    expect(api.preview).toHaveBeenCalledTimes(2);
  });
});
