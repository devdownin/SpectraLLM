import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Optimization from './Optimization';
import { ablationApi } from '../services/api';
import i18n from '../i18n';

vi.mock('../components/charts/AblationCharts', () => ({ default: () => <p>Charts</p> }));
vi.mock('../components/CalibrationWizard', () => ({ default: () => <p>Wizard</p> }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }));
vi.mock('../services/api', () => ({
  fineTuningApi: { getModels: vi.fn().mockResolvedValue({ data: [] }) },
  ablationApi: { listJobs: vi.fn(), getJob: vi.fn() },
}));

const time = '2026-10-10T12:00:00Z';
const arm = (label: string, extra: Record<string, unknown> = {}) => ({
  label, model: 'm', useRag: true, overrides: null,
  quality: { avgScore: 8, hallucinationRate: 0, refusalAccuracy: 1, scoresByCategory: {} },
  retrieval: { evaluatedQuestions: 1, hitRate: 1, mrr: 1, recallAtK: 1 },
  avgLatencyMs: 100, p50LatencyMs: 100, avgContextTokens: 100, runs: 1, stdDev: {}, appliedCounts: {}, ...extra,
});
const job = (report: Record<string, unknown>) => ({
  jobId: 'cal', label: 'Calibration', status: 'COMPLETED', createdAt: time, completedAt: time,
  report: { benchmarkSize: 10, startedAt: time, completedAt: time, ...report },
});

beforeEach(async () => {
  sessionStorage.clear();
  vi.clearAllMocks();
  vi.mocked(ablationApi.listJobs).mockResolvedValue({ data: [] } as never);
  await i18n.changeLanguage('en');
});

function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/optimization?job=cal']}><Optimization /></MemoryRouter></QueryClientProvider>);
}

it('shows the recommended arm with the .env lines that reproduce it, and copies them', async () => {
  vi.mocked(ablationApi.getJob).mockResolvedValue({ data: job({
    recommendedArm: 'best', objective: '0.35·recall',
    arms: [
      arm('baseline', { faithfulness: 0.5 }),
      arm('best', { faithfulness: 0.75, overrides: { rerankMinScore: 0.2, neighborWindow: 1, dedupStrategy: 'none' } }),
    ],
  }) } as never);
  const user = userEvent.setup();
  show();

  const banner = await screen.findByTestId('recommended-arm');
  expect(banner).toHaveTextContent('Recommended setting: best');
  expect(banner).toHaveTextContent('Objective maximised: 0.35·recall');
  const snippet = banner.querySelector('pre')!.textContent!;
  expect(snippet).toContain('SPECTRA_RERANKER_MIN_SCORE=0.2');
  expect(snippet).toContain('SPECTRA_RAG_NEIGHBOR_WINDOW=1');
  expect(snippet).toContain('SPECTRA_SEMANTIC_DEDUP_ENABLED=false');

  const write = vi.spyOn(navigator.clipboard, 'writeText');
  await user.click(within(banner).getByRole('button', { name: 'Copy for .env' }));
  expect(write).toHaveBeenCalledWith(snippet + '\n');

  // La fidélité des citations est une colonne du rapport, avec son delta.
  const table = screen.getByRole('table');
  expect(within(table).getByText('75%')).toBeInTheDocument();
  expect(within(table).getByText('+25 pts')).toBeInTheDocument();
});

it('says so when the best arm changes nothing, and shows no banner without a recommendation', async () => {
  vi.mocked(ablationApi.getJob).mockResolvedValue({ data: job({
    recommendedArm: 'baseline', arms: [arm('baseline'), arm('other')],
  }) } as never);
  show();
  const banner = await screen.findByTestId('recommended-arm');
  expect(banner).toHaveTextContent('changes no setting');
  expect(within(banner).queryByRole('button', { name: 'Copy for .env' })).toBeNull();
});

it('leaves the report as before when the server recommends nothing', async () => {
  vi.mocked(ablationApi.getJob).mockResolvedValue({ data: job({ arms: [arm('baseline')] }) } as never);
  show();
  await screen.findByRole('dialog');
  expect(screen.queryByTestId('recommended-arm')).toBeNull();
  // Aucune citation vérifiée : la colonne existe, vide, et ne promet aucun delta.
  expect(within(screen.getByRole('table')).getAllByText('—').length).toBeGreaterThan(0);
});
