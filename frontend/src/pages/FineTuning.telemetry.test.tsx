import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '../i18n';
import i18n from 'i18next';

/**
 * Rattachement du suivi au job affiché (constat S9).
 *
 * Le flux `/api/sse/training-logs` est unique et global. Tant que ses évènements ne portaient pas
 * de `jobId`, la page affichait la sortie de n'importe quel job en cours comme si elle était celle
 * du job consulté — et cliquer sur un job de l'historique lui laissait la courbe du précédent,
 * les deux séries vivant dans l'état de la page.
 */

const RUNNING = {
  jobId: 'job-en-cours', status: 'TRAINING', modelName: 'spectra-domain', baseModel: 'phi3',
  datasetSize: 120, currentStep: 'Entraînement epoch 1/3', currentEpoch: 0.33, totalEpochs: 3,
  loss: 1.2, evalLoss: null, outputPath: null, reportPath: null, error: null, failedPhase: null,
  createdAt: '2026-08-05T10:00:00Z', completedAt: null,
  parameters: { loraRank: 64, loraAlpha: 128, epochs: 3, learningRate: 2e-4, minConfidence: 0.8, valSplit: 0.1 },
};

const OLD_FAILED = {
  ...RUNNING,
  jobId: 'job-ancien', status: 'FAILED', modelName: 'spectra-ancien',
  currentStep: 'Échoué', error: 'Dataset vide après filtrage', failedPhase: 'EXPORTING_DATASET',
  createdAt: '2026-08-04T09:00:00Z', completedAt: '2026-08-04T09:02:00Z',
};

vi.mock('../services/api', () => ({
  fineTuningApi: {
    getJobs: vi.fn(() => Promise.resolve({ data: [RUNNING, OLD_FAILED] })),
    getJob: vi.fn(() => Promise.resolve({ data: RUNNING })),
    createJob: vi.fn(),
    cancelJob: vi.fn(),
    getBaseModels: vi.fn(() => Promise.resolve({ data: [] })),
  },
  configApi: {
    getModelConfig: vi.fn(() => Promise.resolve({ data: { model: '' } })),
    getModels: vi.fn(() => Promise.resolve({ data: [] })),
  },
  recipeApi: { list: vi.fn(() => Promise.resolve({ data: [] })), get: vi.fn(), export: vi.fn() },
}));

vi.mock('sonner', () => ({
  toast: { warning: vi.fn(), error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

/** EventSource simulé : le test pousse les lignes lui-même, comme le ferait le backend. */
let sse: FakeEventSource | null = null;
/** Enregistre l'instance courante. Passer `this` en argument évite de l'aliaser (no-this-alias). */
const registerSse = (instance: FakeEventSource) => { sse = instance; };
class FakeEventSource {
  onopen: ((e: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  close = vi.fn();
  constructor() { registerSse(this); }
}
vi.stubGlobal('EventSource', FakeEventSource);

const emit = (log: Record<string, unknown>) =>
  act(() => { sse?.onmessage?.({ data: JSON.stringify(log) }); });

import FineTuning from './FineTuning';

beforeEach(() => {
  vi.clearAllMocks();
  sse = null;
  i18n.changeLanguage('en');
});

describe('FineTuning — télémétrie rattachée à son job', () => {
  it('affiche les lignes du job suivi et écarte celles d\'un autre', async () => {
    render(<FineTuning />);
    await screen.findByRole('heading', { name: 'spectra-domain' });

    emit({ level: 'INFO', message: 'ligne du job suivi', timestamp: '10:00:01', jobId: 'job-en-cours' });
    emit({ level: 'INFO', message: 'ligne d\'un autre job', timestamp: '10:00:02', jobId: 'job-autre' });
    emit({ level: 'INFO', message: 'message global', timestamp: '10:00:03', jobId: null });

    expect(await screen.findByText('ligne du job suivi')).toBeInTheDocument();
    expect(screen.getByText('message global')).toBeInTheDocument();
    expect(screen.queryByText('ligne d\'un autre job')).not.toBeInTheDocument();
  });

  it('repart d\'un moniteur vide quand on sélectionne un autre job', async () => {
    render(<FineTuning />);
    await screen.findByRole('heading', { name: 'spectra-domain' });
    emit({ level: 'INFO', message: 'ligne du job suivi', timestamp: '10:00:01', jobId: 'job-en-cours' });
    expect(await screen.findByText('ligne du job suivi')).toBeInTheDocument();

    // Clic sur le job échoué de la veille, dans l'historique.
    fireEvent.click(screen.getByRole('cell', { name: 'spectra-ancien' }));

    await waitFor(() =>
      expect(screen.queryByText('ligne du job suivi')).not.toBeInTheDocument());
    // Et l'on ne prétend pas attendre des évènements qui ne viendront jamais.
    expect(screen.getByText(/telemetry is not retained/i)).toBeInTheDocument();
  });

  it('situe l\'échec d\'un ancien job sur sa phase réelle', async () => {
    render(<FineTuning />);
    await screen.findByRole('heading', { name: 'spectra-domain' });
    fireEvent.click(screen.getByRole('cell', { name: 'spectra-ancien' }));

    // failedPhase = EXPORTING_DATASET : c'est l'étape « Export » qui porte l'erreur, et non
    // « Import » comme le faisait l'index constant d'avant.
    const exportStep = await screen.findByText('Export');
    const importStep = screen.getByText('Import');
    expect(exportStep.className).toContain('text-error');
    expect(importStep.className).not.toContain('text-error');
  });
});
