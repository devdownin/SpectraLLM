import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import i18n from '../i18n';
import Sources from './Sources';
import { SourceWizard } from '../components/sources/SourceWizard';
import type { Source, SourceCatalogEntry } from '../types/sources';
const api = vi.hoisted(() => ({ list: vi.fn(), catalog: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), test: vi.fn(), testSaved: vi.fn(), sync: vi.fn(), cancel: vi.fn(), runs: vi.fn(), documents: vi.fn(), document: vi.fn(), preview: vi.fn() }));
vi.mock('../services/sources', () => ({ sourcesApi: api }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
const catalog: SourceCatalogEntry[] = [
  { type: 'LOCAL_FOLDER', name: 'Local folder', icon: 'folder_open', available: true, capabilities: [] },
  { type: 'S3', name: 'S3 / MinIO', icon: 'cloud', available: true, capabilities: [] },
  { type: 'WEB', name: 'Website', icon: 'language', available: true, capabilities: [] },
];
const source: Source = {
  id: 'source-1', name: 'Internal procedures', type: 'LOCAL_FOLDER', collection: 'spectra_documents', enabled: true,
  intervalMinutes: 60, config: { rootPath: '/app/sources/docs', extensions: [], excludePatterns: [], maxDocuments: 1000, maxDepth: 2, maxFileSizeMb: 25 },
  status: 'SUCCESS', documentCount: 12, lastSuccessAt: '2026-11-06T10:00:00Z', nextSyncAt: '2026-11-06T11:00:00Z', lastError: null, lastRun: null,
};
function renderPage(route = '/sources') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><Sources /></MemoryRouter></QueryClientProvider>);
}
beforeEach(async () => {
  vi.resetAllMocks();
  await i18n.changeLanguage('en');
  api.catalog.mockResolvedValue({ data: catalog });
  api.list.mockResolvedValue({ data: [source] });
  api.test.mockResolvedValue({ data: { success: true, message: 'Bucket is readable', sampleCount: 3 } });
  api.create.mockResolvedValue({ data: { ...source, id: 'created', type: 'S3' } });
  api.update.mockResolvedValue({ data: { ...source, enabled: false } });
  api.cancel.mockResolvedValue({});
  api.runs.mockResolvedValue({ data: [] });
  api.documents.mockResolvedValue({ data: { content: [], number: 0, totalPages: 0, totalElements: 0 } });
});
async function configureS3() {
  const user = userEvent.setup(); const onSaved = vi.fn();
  render(<SourceWizard initialType="S3" catalog={catalog} onClose={vi.fn()} onSaved={onSaved} />);
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  await user.type(screen.getByRole('textbox', { name: 'Source name' }), 'Product archive');
  await user.type(screen.getByRole('textbox', { name: 'Bucket' }), 'knowledge');
  await user.type(screen.getByLabelText('Access key environment variable', { exact: true }), 'SPECTRA_S3_ACCESS_KEY');
  await user.type(screen.getByLabelText('Secret key environment variable', { exact: true }), 'SPECTRA_S3_SECRET_KEY');
  await user.type(screen.getByLabelText('File extensions'), 'pdf, .md');
  await user.click(screen.getByRole('button', { name: 'Continue' }));
  return { user, onSaved };
}
describe('Source configuration', () => {
  it('checks access before saving a bucket and sends server environment references', async () => {
    const { user, onSaved } = await configureS3();
    expect(screen.getByRole('button', { name: 'Save source' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Test connection' }));
    expect(await screen.findByText('Connection successful')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save source' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledOnce());
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ name: 'Product archive', type: 'S3', config: expect.objectContaining({ bucket: 'knowledge', accessKeyEnv: 'SPECTRA_S3_ACCESS_KEY', secretKeyEnv: 'SPECTRA_S3_SECRET_KEY', extensions: ['pdf', 'md'] }) }));
  });
  it('requires a new access check after editing a tested configuration', async () => {
    const { user } = await configureS3();
    await user.click(screen.getByRole('button', { name: 'Test connection' }));
    await screen.findByText('Connection successful');
    await user.click(screen.getByRole('button', { name: 'Back' }));
    await user.clear(screen.getByRole('textbox', { name: 'Bucket' }));
    await user.type(screen.getByRole('textbox', { name: 'Bucket' }), 'other-bucket');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByRole('button', { name: 'Save source' })).toBeDisabled();
    expect(api.create).not.toHaveBeenCalled();
  });
  it('keeps an inaccessible source unsaved and shows the reason', async () => {
    api.test.mockResolvedValue({ data: { success: false, message: 'Access denied by the bucket policy', sampleCount: 0 } });
    const { user } = await configureS3();
    await user.click(screen.getByRole('button', { name: 'Test connection' }));
    expect(await screen.findByText('Access denied by the bucket policy')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save source' })).toBeDisabled();
    expect(api.create).not.toHaveBeenCalled();
  });
});
describe('Sources workspace', () => {
  it('pauses a source without losing its scope, schedule or collection', async () => {
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Pause Internal procedures' }));
    await waitFor(() => expect(api.update).toHaveBeenCalledWith('source-1', { name: source.name, type: source.type, collection: source.collection, enabled: false, intervalMinutes: 60, config: source.config }));
  });
  it('offers stopping a running sync before configuration changes', async () => {
    api.list.mockResolvedValue({ data: [{ ...source, status: 'RUNNING' }] });
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'View details' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).queryByRole('button', { name: 'Configure' })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Delete source' })).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: 'Stop sync' }));
    await waitFor(() => expect(api.cancel).toHaveBeenCalledWith('source-1'));
  });
  it('distinguishes an API failure from an empty source collection', async () => {
    api.list.mockRejectedValue(new Error('offline')); renderPage();
    expect(await screen.findByText('Sources are unavailable')).toBeInTheDocument();
    expect(screen.queryByText('Your knowledge starts here')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeEnabled();
  });
});

it('opens a cited source document and displays its published text safely', async () => {
  api.document.mockResolvedValue({ data: { id: 'doc-9', externalId: 'guide.md', title: 'Architecture guide', sourceUrl: 'javascript:alert(1)', revision: 'r1', indexedAt: '2026-11-06T10:00:00Z', lastSeenAt: null, status: 'INDEXED', error: null } });
  api.preview.mockResolvedValue({ data: { chunks: ['The published architecture uses one ingestion pipeline.'], truncated: true } });
  renderPage('/sources?source=source-1&document=doc-9');
  expect(await screen.findByText('The published architecture uses one ingestion pipeline.')).toBeInTheDocument();
  expect(api.document).toHaveBeenCalledWith('source-1', 'doc-9');
  expect(api.preview).toHaveBeenCalledWith('source-1', 'doc-9');
  const dialog = screen.getByRole('dialog');
  expect(within(dialog).getByText('Cited document')).toBeInTheDocument();
  expect(within(dialog).queryByRole('link', { name: /javascript/ })).not.toBeInTheDocument();
  expect(within(dialog).getByText(/Preview limited/)).toBeInTheDocument();
});
