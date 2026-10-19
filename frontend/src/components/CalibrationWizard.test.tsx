import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import CalibrationWizard from './CalibrationWizard';
import { ablationApi, qualityBenchmarkApi } from '../services/api';
import i18n from '../i18n';
import type { BenchmarkDraftJob, BenchmarkInfo } from '../types/api';

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }));
vi.mock('../services/api', () => ({
  ablationApi: { calibrate: vi.fn() },
  qualityBenchmarkApi: {
    getBenchmark: vi.fn(), saveBenchmark: vi.fn(), startDraft: vi.fn(), getDraftJob: vi.fn(), cancelDraft: vi.fn(),
  },
}));

const info = (over: Partial<BenchmarkInfo> = {}): BenchmarkInfo =>
  ({ source: 'BUNDLED', path: null, total: 20, answerable: 15, unanswerable: 5, withExpectedSources: 15, ...over });

const draftJob = (over: Partial<BenchmarkDraftJob> = {}): BenchmarkDraftJob => ({
  jobId: 'd1', status: 'COMPLETED', total: 2, processed: 2, scannedChunks: 40, file: '/data/draft.jsonl',
  items: [
    { question: 'Quel est le préavis ?', reference: 'Trois mois.', category: 'juridique', answerable: true, expectedSources: ['contrat.pdf'], draft: true },
    { question: 'Quelle est la garantie ?', reference: 'Deux ans.', category: 'juridique', answerable: true, expectedSources: ['notice.pdf'], draft: true },
  ],
  error: null, createdAt: '2026-10-10T12:00:00Z', completedAt: '2026-10-10T12:01:00Z', ...over,
});

beforeEach(async () => {
  vi.clearAllMocks();
  await i18n.changeLanguage('en');
  vi.mocked(qualityBenchmarkApi.getBenchmark).mockResolvedValue({ data: info() } as never);
});

function show() {
  const onLaunched = vi.fn();
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <CalibrationWizard onLaunched={onLaunched} />
    </QueryClientProvider>,
  );
  return onLaunched;
}

describe('CalibrationWizard', () => {
  it('warns that the bundled benchmark says nothing about the user\'s documents', async () => {
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('bundled one');
  });

  it('describes a benchmark from the corpus, and warns when sources are missing', async () => {
    vi.mocked(qualityBenchmarkApi.getBenchmark).mockResolvedValue({
      data: info({ source: 'CURATED', path: '/data/benchmarks/corpus.jsonl', total: 30, answerable: 26, unanswerable: 4, withExpectedSources: 20 }),
    } as never);
    show();
    expect(await screen.findByText(/30 questions — 26 with an answer, 4 out of corpus, 20 naming their source/)).toBeInTheDocument();
    expect(screen.getByText(/Only 20 of 26 answerable questions name their source/)).toBeInTheDocument();
  });

  it('disables drafting when a configured file takes precedence', async () => {
    vi.mocked(qualityBenchmarkApi.getBenchmark).mockResolvedValue({ data: info({ source: 'CONFIGURED', path: '/x.jsonl' }) } as never);
    show();
    await screen.findByText(/takes precedence/);
    expect(screen.queryByRole('button', { name: 'Draft from my corpus' })).toBeNull();
  });

  it('drafts, lets the user review and complete the set, then saves it', async () => {
    vi.mocked(qualityBenchmarkApi.startDraft).mockResolvedValue({ data: { jobId: 'd1' } } as never);
    vi.mocked(qualityBenchmarkApi.getDraftJob).mockResolvedValue({ data: draftJob() } as never);
    vi.mocked(qualityBenchmarkApi.saveBenchmark).mockResolvedValue({ data: info({ source: 'CURATED', total: 3 }) } as never);
    const user = userEvent.setup();
    show();

    await user.click(await screen.findByRole('button', { name: 'Draft from my corpus' }));
    expect(qualityBenchmarkApi.startDraft).toHaveBeenCalledWith(30);

    // Relecture : la question est éditable, la source est montrée.
    const question = await screen.findByLabelText('Question 1');
    expect(screen.getByText('contrat.pdf')).toBeInTheDocument();
    await user.clear(question);
    await user.type(question, 'Quel est le délai de préavis ?');

    // Aucun hors corpus : l'écran le dit, et un hors corpus vide bloque l'enregistrement.
    expect(screen.getByText(/Out of corpus: 0% — aim for 10–20%/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Add an out-of-corpus question' }));
    expect(screen.getByText('1 entries are incomplete (empty question, or no expected answer).')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save as my benchmark' })).toBeDisabled();

    await user.type(screen.getByLabelText('Question 3'), 'Qui dirigera en 2030 ?');
    expect(screen.queryByText(/entries are incomplete/)).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Save as my benchmark' }));

    await waitFor(() => expect(qualityBenchmarkApi.saveBenchmark).toHaveBeenCalledTimes(1));
    const saved = vi.mocked(qualityBenchmarkApi.saveBenchmark).mock.calls[0][0];
    expect(saved).toHaveLength(3);
    expect(saved[0]).toMatchObject({ question: 'Quel est le délai de préavis ?', reference: 'Trois mois.', expectedSources: ['contrat.pdf'] });
    expect(saved[2]).toMatchObject({ question: 'Qui dirigera en 2030 ?', answerable: false, reference: null });
    // Une fois enregistré, la relecture se referme.
    await waitFor(() => expect(screen.queryByLabelText('Question 1')).toBeNull());
  });

  it('shows progress with a cancel button while drafting', async () => {
    vi.mocked(qualityBenchmarkApi.startDraft).mockResolvedValue({ data: { jobId: 'd1' } } as never);
    vi.mocked(qualityBenchmarkApi.getDraftJob).mockResolvedValue({ data: draftJob({ status: 'RUNNING', processed: 1, total: 4, items: [] }) } as never);
    vi.mocked(qualityBenchmarkApi.cancelDraft).mockResolvedValue({} as never);
    const user = userEvent.setup();
    show();

    await user.click(await screen.findByRole('button', { name: 'Draft from my corpus' }));
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent('1/4 questions');
    await user.click(within(status).getByRole('button', { name: 'Cancel' }));
    expect(qualityBenchmarkApi.cancelDraft).toHaveBeenCalledWith('d1');
    expect(screen.getByRole('button', { name: 'Draft from my corpus' })).toBeDisabled();
  });

  it('reports a failed draft and an empty one without locking the screen', async () => {
    vi.mocked(qualityBenchmarkApi.startDraft).mockResolvedValue({ data: { jobId: 'd1' } } as never);
    vi.mocked(qualityBenchmarkApi.getDraftJob).mockResolvedValue({ data: draftJob({ status: 'FAILED', error: 'chroma absent', items: [] }) } as never);
    const user = userEvent.setup();
    show();

    await user.click(await screen.findByRole('button', { name: 'Draft from my corpus' }));
    expect(await screen.findByText('Drafting failed: chroma absent')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run calibration' })).toBeEnabled();
  });

  it('launches the grid with ordered values and hands back the job', async () => {
    vi.mocked(ablationApi.calibrate).mockResolvedValue({ data: { jobId: 'cal-1' } } as never);
    const user = userEvent.setup();
    const onLaunched = show();

    expect(await screen.findByText(/3 combinations × 20 questions/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '0.3' }));
    await user.click(screen.getByRole('button', { name: '1' }));
    await user.click(screen.getByRole('button', { name: 'mmr' }));
    expect(screen.getByText(/4 combinations × 20 questions/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Run calibration' }));

    await waitFor(() => expect(onLaunched).toHaveBeenCalledWith('cal-1'));
    expect(ablationApi.calibrate).toHaveBeenCalledWith({
      rerankMinScores: [0, 0.1, 0.2, 0.3], neighborWindows: [1], dedupStrategies: ['mmr'], maxContextChunks: 5, runs: 1,
    });
  });

  it('refuses a grid over the cap', async () => {
    const user = userEvent.setup();
    show();
    await screen.findByText(/combinations/);
    for (const name of ['0.3', '0.5', '1', '2', 'none', 'mmr']) {
      const buttons = screen.getAllByRole('button', { name });
      await user.click(buttons[0]);
    }
    expect(screen.getByText('20 combinations: 16 at most — narrow one setting.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Run calibration' })).toBeDisabled();
  });
});
