import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Documents from './Documents';
import { gedApi } from '../services/api';
import '../i18n';
import i18n from '../i18n';

vi.mock('../services/api', () => ({ gedApi: {
  getStats: vi.fn().mockResolvedValue({ data: {} }), getClassificationConfig: vi.fn().mockResolvedValue({ data: {} }), listDocuments: vi.fn(), deleteDocument: vi.fn().mockResolvedValue({ data: {} }),
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

function batches(secondFails = false) {
  vi.mocked(gedApi.listDocuments).mockImplementation(async params => {
    const page = Number(params?.page ?? 0);
    if (secondFails && page === 1) throw new Error('batch offline');
    return { data: { page, totalPages: 2, totalElements: 75, content: Array.from({ length: page === 0 ? 50 : 25 }, (_, n) => ({ sha256: `sha-${page * 50 + n}`, fileName: `doc-${String(page * 50 + n).padStart(3, '0')}.pdf`, format: 'PDF', lifecycle: 'INGESTED', qualityScore: 0.8, ingestedAt: '2026-10-11T00:00:00Z', chunksCreated: 5, tags: [] })) } } as never;
  });
}
it('selects only the current page and keeps the other page selection', async () => {
  batches(); show(); await screen.findByRole('button', { name: 'doc-000.pdf' });
  await userEvent.click(screen.getByRole('checkbox', { name: 'Select this page' }));
  expect(screen.getByText('50 selected on this page')).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: /Load 25 more/ }));
  await screen.findByText('75 filtered results');
  await userEvent.click(screen.getByRole('button', { name: 'Next' }));
  expect(screen.getByText('0 selected on this page')).toBeVisible();
  expect(screen.getByText('50 selected in total')).toBeVisible();
  await userEvent.click(screen.getByRole('checkbox', { name: 'Select this page' }));
  expect(screen.getByText('25 selected on this page')).toBeVisible();
  expect(screen.getByText('75 selected in total')).toBeVisible();
  await userEvent.click(screen.getByRole('checkbox', { name: 'Select this page' }));
  expect(screen.getByText('50 selected in total')).toBeVisible();
  await userEvent.type(screen.getByRole('searchbox'), 'absent');
  await waitFor(() => expect(screen.getByText('0 selected in total')).toBeVisible());
}, 15000);
it('loads every server batch for an explicit global selection', async () => {
  batches(); show(); await screen.findByRole('button', { name: 'doc-000.pdf' });
  await userEvent.click(screen.getByRole('button', { name: 'Select all filtered results' }));
  expect(await screen.findByText('75 selected in total')).toBeVisible();
  expect(screen.getByText('50 selected on this page')).toBeVisible();
  expect(gedApi.listDocuments).toHaveBeenCalledWith(expect.objectContaining({ page: 1 }));
}, 15000);
it('keeps the existing selection when a global-selection batch fails', async () => {
  batches(true); show(); await screen.findByRole('button', { name: 'doc-000.pdf' });
  await userEvent.click(screen.getByRole('checkbox', { name: 'Select doc-000.pdf' }));
  await userEvent.click(screen.getByRole('button', { name: 'Select all filtered results' }));
  await waitFor(() => expect(gedApi.listDocuments).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })));
  await waitFor(() => expect(screen.getByRole('button', { name: 'Select all filtered results' })).not.toHaveTextContent('Loading'));
  expect(screen.getByText('1 selected in total')).toBeVisible();
  expect(screen.getByRole('checkbox', { name: 'Select doc-001.pdf' })).toHaveAttribute('aria-checked', 'false');
}, 15000);
it('does not apply an in-flight global selection after filters change', async () => {
  batches(); const initial = vi.mocked(gedApi.listDocuments).getMockImplementation()!;
  let finish: ((value: never) => void) | undefined;
  vi.mocked(gedApi.listDocuments).mockImplementation(params => Number(params?.page ?? 0) === 1 ? new Promise(resolve => { finish = resolve; }) : initial(params));
  show(); await screen.findByRole('button', { name: 'doc-000.pdf' });
  await userEvent.click(screen.getByRole('button', { name: 'Select all filtered results' }));
  await waitFor(() => expect(finish).toBeDefined());
  await userEvent.type(screen.getByRole('searchbox'), 'absent');
  finish!({ data: { page: 1, totalPages: 2, totalElements: 75, content: [] } } as never);
  await waitFor(() => expect(screen.getByText('0 selected in total')).toBeVisible());
}, 15000);

it('confirms and deletes only the explicitly selected document', async () => {
  batches(); show(); await screen.findByRole('button', { name: 'doc-000.pdf' });
  await userEvent.click(screen.getByRole('checkbox', { name: 'Select doc-000.pdf' }));
  await userEvent.click(screen.getByRole('button', { name: 'Delete', exact: true }));
  const dialog = screen.getByRole('alertdialog', { name: 'Delete 1 document(s)?' });
  expect(gedApi.deleteDocument).not.toHaveBeenCalled();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Delete', exact: true }));
  await waitFor(() => expect(gedApi.deleteDocument).toHaveBeenCalledExactlyOnceWith('sha-0'));
}, 15000);

it('finds filtered matches in later batches even when none are currently loaded', async () => {
  sessionStorage.setItem('spectra.view.documents.qualityMin', '0.9');
  batches(); const initial = vi.mocked(gedApi.listDocuments).getMockImplementation()!;
  vi.mocked(gedApi.listDocuments).mockImplementation(async params => {
    const result = await initial(params);
    result.data.content.forEach(doc => { doc.qualityScore = Number(params?.page ?? 0) === 0 ? 0.5 : 0.95; });
    return result;
  });
  show(); await screen.findByText('No items to display');
  await userEvent.click(screen.getByRole('button', { name: 'Select all filtered results' }));
  expect(await screen.findByText('25 selected in total')).toBeVisible();
  expect(screen.getByRole('checkbox', { name: 'Select doc-050.pdf' })).toHaveAttribute('aria-checked', 'true');
}, 15000);
