import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Documents from './Documents';
import { gedApi } from '../services/api';
import '../i18n';
import i18n from '../i18n';

vi.mock('../services/api', () => ({ gedApi: {
  getStats: vi.fn().mockResolvedValue({ data: {} }), getClassificationConfig: vi.fn().mockResolvedValue({ data: {} }), listDocuments: vi.fn(),
}, commentApi: {} }));
function show() { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter><Documents /></MemoryRouter></QueryClientProvider>); }
beforeEach(async () => { await i18n.changeLanguage('en'); vi.clearAllMocks(); });
it('rehydrates saved search, sort and pagination from server batches on a fresh load', async () => {
  sessionStorage.setItem('spectra.view.documents.search', JSON.stringify('invoice'));
  sessionStorage.setItem('spectra.view.documents.sortMode', JSON.stringify('name'));
  sessionStorage.setItem('spectra.view.documents.page', '1');
  vi.mocked(gedApi.listDocuments).mockImplementation(async params => {
    const page = Number(params?.page ?? 0);
    return { data: { page, totalPages: 2, totalElements: 51, content: Array.from({ length: page === 0 ? 50 : 1 }, (_, n) => ({ sha256: `sha-${page * 50 + n}`, fileName: `invoice-${String(page * 50 + n).padStart(3, '0')}.pdf`, format: 'PDF', lifecycle: 'INGESTED', qualityScore: 0.8, ingestedAt: '2026-10-11T00:00:00Z', chunksCreated: 5, tags: [] })) } } as never;
  });
  const view = show();
  expect(await screen.findByRole('button', { name: 'invoice-050.pdf' })).toBeVisible();
  expect(screen.queryByRole('button', { name: 'invoice-000.pdf' })).not.toBeInTheDocument();
  expect(gedApi.listDocuments).toHaveBeenCalledWith(expect.objectContaining({ page: 1, q: 'invoice' }));
  view.unmount(); show();
  expect(await screen.findByRole('button', { name: 'invoice-050.pdf' })).toBeVisible();
});
it('shows an error instead of an empty corpus and retries the list', async () => {
  vi.mocked(gedApi.listDocuments).mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ data: { content: [], page: 0, totalPages: 1, totalElements: 0 } } as never);
  show(); expect(await screen.findByRole('alert')).toHaveTextContent('The list could not be refreshed');
  await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
  expect(await screen.findByText('No items to display')).toBeVisible();
});
