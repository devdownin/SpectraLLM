import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Playground from './Playground';
import { gedApi, queryApi } from '../services/api';
import type { StreamEvent } from '../services/api';
import i18n from '../i18n';

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }));
vi.mock('../services/api', () => ({
  queryApi: { queryStream: vi.fn(), feedback: vi.fn() },
  gedApi: { listDocuments: vi.fn() },
  configApi: {
    getModelConfig: vi.fn().mockResolvedValue({ data: { model: 'chat' } }),
    getRagConfig: vi.fn().mockResolvedValue({ data: { modules: {} } }),
    setModelConfig: vi.fn(),
  },
  fineTuningApi: { getModels: vi.fn().mockResolvedValue({ data: [] }) },
  healthApi: {
    getServices: vi.fn().mockResolvedValue({ data: [
      { name: 'llama-cpp', available: true }, { name: 'chromadb', available: true },
    ] }),
  },
}));

const sources = [
  { sourceFile: 'contrat.pdf', distance: 0.1, preview: 'La garantie dure deux ans.' },
  { sourceFile: 'tarifs.pdf', distance: 0.2, preview: 'Grille tarifaire.' },
];
const report = {
  citedSentences: 2, supportedSentences: 1, unsupportedSentences: 1, invalidCitations: 0, uncitedSentences: 0,
  faithfulness: 0.5, minScore: 0.2,
  checks: [
    { sentence: 'La garantie dure deux ans [1].', citations: [1], bestScore: 0.93, status: 'SUPPORTED' },
    { sentence: 'Le prix est de 10 € [2].', citations: [2], bestScore: 0.03, status: 'UNSUPPORTED' },
  ],
};

function stream(events: StreamEvent[]) {
  return async function* () { for (const e of events) yield e; };
}
const answer = (extra: Record<string, unknown> = {}, found = sources): StreamEvent[] => [
  { type: 'sources', data: JSON.stringify(found) },
  { type: 'token', data: 'La garantie dure deux ans [1]. Le prix est de 10 € [2].' },
  { type: 'done', data: JSON.stringify({ ragStrategy: 'STANDARD', chunkCount: found.length, ...extra }) },
];

beforeEach(async () => {
  // jsdom n'a pas de mise en page : le Playground fait défiler vers le bas à chaque message.
  Element.prototype.scrollIntoView = vi.fn();
  localStorage.clear(); sessionStorage.clear();
  vi.clearAllMocks();
  await i18n.changeLanguage('en');
  vi.mocked(gedApi.listDocuments).mockResolvedValue({ data: {
    content: [{ fileName: 'contrat.pdf', categories: ['juridique'] }, { fileName: 'tarifs.pdf', categories: ['finance'] }],
    totalElements: 2,
  } } as never);
});

function show() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter><Playground /></MemoryRouter>
    </QueryClientProvider>,
  );
}

async function ask(user: ReturnType<typeof userEvent.setup>, text = 'Quelle garantie ?') {
  await user.type(await screen.findByRole('textbox', { name: 'Message' }), text);
  await user.click(screen.getByRole('button', { name: 'Send message' }));
}

it('flags the unsupported citation chip and details the check under the answer', async () => {
  vi.mocked(queryApi.queryStream).mockImplementation(stream(answer({ citations: report })) as never);
  const user = userEvent.setup();
  show();
  await ask(user);

  const badge = await screen.findByRole('button', { name: '1/2 sentences backed' });
  expect(screen.getByRole('button', { name: 'Source 1' })).toHaveAttribute('data-citation-status', 'supported');
  expect(screen.getByRole('button', { name: 'Source 2 — not supported by the passage' })).toHaveAttribute('data-citation-status', 'unsupported');

  await user.click(badge);
  const panel = screen.getByTestId('citation-report');
  expect(within(panel).getByText(/Le prix est de 10/)).toBeInTheDocument();
  expect(within(panel).getByText('score 0.03 (threshold 0.20)')).toBeInTheDocument();
});

it('shows no verification when the server could not check the citations', async () => {
  vi.mocked(queryApi.queryStream).mockImplementation(stream(answer({ citations: null })) as never);
  const user = userEvent.setup();
  show();
  await ask(user);

  await screen.findByRole('button', { name: 'Source 1' });
  expect(screen.queryByTestId('citation-report')).toBeNull();
  expect(screen.getByRole('button', { name: 'Source 2' })).not.toHaveAttribute('data-citation-status');
});

it('sends the chosen filter with the question and remembers it on the answer', async () => {
  vi.mocked(queryApi.queryStream).mockImplementation(stream(answer()) as never);
  const user = userEvent.setup();
  show();

  await user.click(await screen.findByRole('button', { name: 'Filter' }));
  await user.click(await screen.findByRole('button', { name: /juridique/ }));
  expect(screen.getByRole('button', { name: 'Filter active' })).toBeInTheDocument();
  await ask(user);

  await waitFor(() => expect(queryApi.queryStream).toHaveBeenCalledTimes(1));
  const args = vi.mocked(queryApi.queryStream).mock.calls[0];
  expect(args[9]).toEqual({ categories: ['juridique'] });
  // Le filtre survit à l'envoi : on ne l'oublie pas d'une question à l'autre.
  expect(screen.getByRole('button', { name: 'Remove juridique' })).toBeInTheDocument();
});

it('sends no filter when none is set', async () => {
  vi.mocked(queryApi.queryStream).mockImplementation(stream(answer()) as never);
  const user = userEvent.setup();
  show();
  await ask(user);

  await waitFor(() => expect(queryApi.queryStream).toHaveBeenCalledTimes(1));
  expect(vi.mocked(queryApi.queryStream).mock.calls[0][9]).toBeUndefined();
});

it('tells the user when the filter matched no document', async () => {
  vi.mocked(queryApi.queryStream).mockImplementation(stream([
    { type: 'sources', data: '[]' },
    { type: 'token', data: 'Aucun document pertinent.' },
    { type: 'done', data: JSON.stringify({ ragStrategy: 'STANDARD', chunkCount: 0 }) },
  ]) as never);
  const user = userEvent.setup();
  show();

  await user.click(await screen.findByRole('button', { name: 'Filter' }));
  await user.click(await screen.findByRole('button', { name: /finance/ }));
  await ask(user);

  expect(await screen.findByText(/No document matches this filter/)).toBeInTheDocument();
});

it('restores the filter of the tab session, and clears it when the collection changes', async () => {
  sessionStorage.setItem('spectra_filter', JSON.stringify({ categories: ['rh'], sources: [], from: '', to: '' }));
  vi.mocked(queryApi.queryStream).mockImplementation(stream(answer()) as never);
  show();

  expect(await screen.findByRole('button', { name: 'Remove rh' })).toBeInTheDocument();
  localStorage.setItem('spectra_workspace_collection', 'autre');
  window.dispatchEvent(new Event('spectra:collection'));

  await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove rh' })).toBeNull());
  expect(sessionStorage.getItem('spectra_filter')).toBeNull();
});
