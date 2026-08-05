import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { toast } from 'sonner';
import '../i18n';
import i18n from 'i18next';

/**
 * Pilotage d'un entraînement depuis sa propre page : arrêter un job en cours (S15) et voir
 * pourquoi une soumission est refusée (S16).
 *
 * L'API et le centre d'activité global permettaient déjà l'annulation ; la page dédiée était
 * le seul endroit d'où l'on ne pouvait PAS arrêter un travail qui monopolise la machine des
 * heures durant et bloque toute autre soumission.
 */

const RUNNING_JOB = {
  jobId: 'job-1234-5678', status: 'TRAINING', modelName: 'spectra-domain', baseModel: 'phi3',
  datasetSize: 120, currentStep: 'Entraînement epoch 1/3', currentEpoch: 0.33, totalEpochs: 3,
  loss: 1.2, evalLoss: null, outputPath: null, reportPath: null, error: null,
  createdAt: '2026-08-05T10:00:00Z', completedAt: null,
  parameters: { loraRank: 64, loraAlpha: 128, epochs: 3, learningRate: 2e-4, minConfidence: 0.8, valSplit: 0.1 },
};

const cancelJob = vi.fn(() => Promise.resolve({ data: {} }));
const createJob = vi.fn();

vi.mock('../services/api', () => ({
  fineTuningApi: {
    getJobs: vi.fn(() => Promise.resolve({ data: [RUNNING_JOB] })),
    getJob: vi.fn(() => Promise.resolve({ data: RUNNING_JOB })),
    createJob: (job: unknown) => createJob(job),
    cancelJob: (id: string) => cancelJob(id),
    getBaseModels: vi.fn(() => Promise.resolve({ data: [] })),
    getAvailability: vi.fn(() => Promise.resolve({ data: { available: true, reason: null } })),
  },
  configApi: {
    getModelConfig: vi.fn(() => Promise.resolve({ data: { model: '' } })),
    getModels: vi.fn(() => Promise.resolve({ data: [] })),
  },
  recipeApi: {
    list: vi.fn(() => Promise.resolve({ data: [] })),
    get: vi.fn(),
    export: vi.fn(),
  },
}));

vi.mock('sonner', () => ({
  toast: { warning: vi.fn(), error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

// jsdom n'implémente pas EventSource : le flux de télémétrie est neutralisé, ces cas portent
// sur le pilotage, pas sur le transport SSE.
class FakeEventSource {
  close = vi.fn();
  onopen: ((e: unknown) => void) | null = null;
  onmessage: ((e: unknown) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
}
vi.stubGlobal('EventSource', FakeEventSource);

import FineTuning from './FineTuning';

beforeEach(() => {
  vi.clearAllMocks();
  i18n.changeLanguage('en');
});

describe('FineTuning — arrêt d\'un job en cours', () => {
  it('propose l\'arrêt, demande confirmation, puis appelle l\'API', async () => {
    render(<FineTuning />);

    // Le job en cours est restauré depuis l'historique au montage.
    const stop = await screen.findByTitle('Stop');
    fireEvent.click(stop);

    // Un entraînement interrompu est perdu : la confirmation n'est pas décorative.
    const confirm = await screen.findByRole('button', { name: /stop training/i });
    fireEvent.click(confirm);

    await waitFor(() => expect(cancelJob).toHaveBeenCalledWith('job-1234-5678'));
    expect(toast.info).toHaveBeenCalled();
  });

  it('n\'appelle rien si la confirmation est refusée', async () => {
    render(<FineTuning />);

    fireEvent.click(await screen.findByTitle('Stop'));
    fireEvent.click(await screen.findByRole('button', { name: /^cancel$/i }));

    await waitFor(() => expect(screen.queryByText(/stop training\?/i)).not.toBeInTheDocument());
    expect(cancelJob).not.toHaveBeenCalled();
  });
});

describe('FineTuning — refus de soumission', () => {
  it('affiche le motif du 409 au lieu du code HTTP', async () => {
    // Le contrôleur répond en ProblemDetail ; ne lire que « detail » suffisait autrefois à
    // perdre le message, la route 409 renvoyant alors une map { error }.
    createJob.mockRejectedValueOnce({
      response: { data: { detail: 'Un entraînement est déjà en cours — un seul job à la fois.' } },
      message: 'Request failed with status code 409',
    });

    render(<FineTuning />);
    fireEvent.click(await screen.findByRole('button', { name: /new training job/i }));
    fireEvent.click(await screen.findByRole('button', { name: /launch training/i }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith(
      'Submission error',
      { description: 'Un entraînement est déjà en cours — un seul job à la fois.' },
    ));
  });
});
