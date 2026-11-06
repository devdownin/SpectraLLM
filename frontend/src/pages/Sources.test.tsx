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

describe('Source management workflows', () => {
  it('starts a sync only on request and replaces the action with stopping once running', async () => {
    api.sync.mockResolvedValue({ data: { id: 'run-1', status: 'RUNNING' } });
    const user = userEvent.setup(); renderPage();
    const sync = await screen.findByRole('button', { name: 'Sync now' });
    expect(api.sync).not.toHaveBeenCalled();
    api.list.mockResolvedValue({ data: [{ ...source, status: 'RUNNING' }] });
    await user.click(sync);
    await waitFor(() => expect(api.sync).toHaveBeenCalledWith(source.id));
    expect(await screen.findByRole('button', { name: 'Stop sync' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Sync now' })).not.toBeInTheDocument();
  });

  it('keeps source actions usable after a synchronization request fails', async () => {
    api.sync.mockRejectedValue({ response: { status: 429, data: { detail: 'Other sources are synchronizing. Try again shortly.' } } });
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Sync now' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Other sources are synchronizing');
    expect(screen.getByRole('button', { name: 'Sync now' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Stop sync' })).not.toBeInTheDocument();
  });

  it('resumes a paused source and retains its interval and file filters', async () => {
    api.list.mockResolvedValue({ data: [{ ...source, enabled: false }] });
    const user = userEvent.setup(); renderPage();
    expect(await screen.findByRole('button', { name: 'Sync now' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Resume Internal procedures' }));
    await waitFor(() => expect(api.update).toHaveBeenCalledWith(source.id, expect.objectContaining({ enabled: true, config: source.config, intervalMinutes: 60 })));
  });

  it('reports source access checks without triggering an indexing run', async () => {
    api.testSaved.mockResolvedValueOnce({ data: { success: false, message: 'Source account no longer has access', sampleCount: 0 } })
      .mockResolvedValueOnce({ data: { success: true, message: 'Read access restored', sampleCount: 1 } });
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'View details' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Test connection' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Source account no longer has access');
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Test connection' }));
    await waitFor(() => expect(api.testSaved).toHaveBeenCalledTimes(2));
    expect(api.sync).not.toHaveBeenCalled();
    expect(api.update).not.toHaveBeenCalled();
  });

  it('requires deletion confirmation and keeps the source available after a failed deletion', async () => {
    api.remove.mockRejectedValueOnce({ response: { status: 503, data: { detail: 'Vector cleanup is unavailable; retry deletion.' } } }).mockResolvedValueOnce({});
    const user = userEvent.setup(); renderPage('/sources?source=source-1');
    const details = await screen.findByRole('dialog');
    await user.click(within(details).getByRole('button', { name: 'Delete source' }));
    let confirmation = screen.getByRole('alertdialog');
    expect(confirmation).toHaveTextContent('Internal procedures');
    expect(confirmation).toHaveTextContent('12 indexed document(s)');
    await user.click(within(confirmation).getByRole('button', { name: 'Cancel' }));
    expect(api.remove).not.toHaveBeenCalled();
    await user.click(within(details).getByRole('button', { name: 'Delete source' }));
    confirmation = screen.getByRole('alertdialog');
    await user.click(within(confirmation).getByRole('button', { name: 'Delete source' }));
    expect(await within(confirmation).findByRole('alert')).toHaveTextContent('Vector cleanup is unavailable');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(within(confirmation).getByRole('button', { name: 'Delete source' })).toBeEnabled();
    api.list.mockResolvedValue({ data: [] });
    await user.click(within(confirmation).getByRole('button', { name: 'Delete source' }));
    expect(await screen.findByText('Your knowledge starts here')).toBeInTheDocument();
    expect(api.remove).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.queryByText('This source no longer exists. Choose another source above.')).not.toBeInTheDocument();
  });

  it('edits only mutable settings and retains the form when saving is temporarily refused', async () => {
    api.update.mockRejectedValueOnce({ response: { status: 409, data: { detail: 'A synchronization just started. Stop it before editing.' } } });
    const user = userEvent.setup(); renderPage('/sources?source=source-1');
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Configure' }));
    expect(screen.getByRole('textbox', { name: 'Knowledge collection' })).toBeDisabled();
    const name = screen.getByRole('textbox', { name: 'Source name' });
    await user.clear(name); await user.type(name, 'Operating procedures');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Synchronization schedule' }), '15');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Test connection' }));
    await screen.findByText('Connection successful');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A synchronization just started');
    expect(screen.getByRole('dialog')).toHaveTextContent('Operating procedures');
    expect(api.update).toHaveBeenCalledWith(source.id, expect.objectContaining({ name: 'Operating procedures', collection: 'spectra_documents', type: 'LOCAL_FOLDER', intervalMinutes: 15 }));
    expect(api.create).not.toHaveBeenCalled();
  });

  it('creates a manual website source after recovering from a network check and save failure', async () => {
    api.list.mockResolvedValue({ data: [] });
    api.test.mockRejectedValueOnce(new Error('Network disconnected')).mockResolvedValueOnce({ data: { success: true, message: 'Site is readable', sampleCount: 2 } });
    const webSource: Source = { ...source, id: 'web-1', name: 'Support docs', type: 'WEB', intervalMinutes: 0, config: { ...source.config, rootPath: undefined, url: 'https://docs.example.com/', sitemapUrl: 'https://docs.example.com/sitemap.xml', excludePatterns: ['**/archive/**'], maxDepth: 3 } };
    api.create.mockRejectedValueOnce({ response: { status: 503, data: { detail: 'Database temporarily unavailable' } } }).mockResolvedValueOnce({ data: webSource });
    const user = userEvent.setup(); renderPage();
    await user.click(await screen.findByRole('button', { name: 'Connect Website' }));
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.type(screen.getByRole('textbox', { name: 'Source name' }), 'Support docs');
    await user.type(screen.getByRole('textbox', { name: 'Starting URL' }), 'https://docs.example.com/');
    await user.type(screen.getByRole('textbox', { name: 'Sitemap URL (optional)' }), 'https://docs.example.com/sitemap.xml');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Synchronization schedule' }), '0');
    await user.click(screen.getByText('Filters and safety limits'));
    await user.type(screen.getByRole('textbox', { name: 'Excluded paths' }), '**/archive/**');
    const depth = screen.getByRole('spinbutton', { name: 'Folder or link depth' });
    await user.clear(depth); await user.type(depth, '3');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await user.click(screen.getByRole('button', { name: 'Test connection' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Network disconnected');
    expect(screen.getByRole('button', { name: 'Save source' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Test connection' }));
    await screen.findByText('Connection successful');
    await user.click(screen.getByRole('button', { name: 'Save source' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Database temporarily unavailable');
    expect(screen.getByRole('dialog')).toHaveTextContent('https://docs.example.com/');
    api.list.mockResolvedValue({ data: [webSource] });
    await user.click(screen.getByRole('button', { name: 'Save source' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add a source' })).not.toBeInTheDocument());
    expect(api.create).toHaveBeenLastCalledWith(expect.objectContaining({ name: 'Support docs', type: 'WEB', intervalMinutes: 0, config: expect.objectContaining({ url: 'https://docs.example.com/', sitemapUrl: 'https://docs.example.com/sitemap.xml', excludePatterns: ['**/archive/**'], maxDepth: 3 }) }));
    expect(api.sync).not.toHaveBeenCalled();
    expect(await screen.findByRole('dialog', { name: 'Support docs' })).toBeInTheDocument();
  });

  it('lets the user recover a missing catalogue and then filter or clear source results', async () => {
    api.catalog.mockRejectedValueOnce(new Error('Catalogue offline')).mockResolvedValueOnce({ data: catalog });
    const user = userEvent.setup(); renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the connector catalogue');
    expect(screen.getByRole('button', { name: 'Add a source' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('button', { name: 'Connect Website' })).toBeEnabled();
    await user.type(screen.getByRole('searchbox', { name: 'Search a source or location' }), 'unknown source');
    expect(screen.getByText('No sources match these filters')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(screen.getByRole('button', { name: source.name })).toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'PAUSED');
    expect(screen.queryByRole('button', { name: source.name })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(screen.getByRole('button', { name: source.name })).toBeInTheDocument();
  });
});
