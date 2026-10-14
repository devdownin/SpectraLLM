import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '../i18n';
import i18n from 'i18next';

/**
 * Enchaînement de l'évaluation depuis le formulaire.
 *
 * <p>Le serveur refuse « évaluer » sans « exporter en GGUF » : sans enregistrement, le modèle
 * entraîné n'est pas servable, et l'évaluer reviendrait à interroger le modèle <i>actif</i> tout
 * en attribuant le score au nouveau. Le formulaire doit rendre cette combinaison inatteignable —
 * une case cochable dont on sait qu'elle produira un 400 est un piège, pas un choix.
 */

const COMPLETED_JOB = {
  jobId: 'job-eval', status: 'COMPLETED', modelName: 'spectra-domain', baseModel: 'phi3',
  datasetSize: 120, currentStep: 'Terminé', currentEpoch: 3, totalEpochs: 3,
  loss: 0.4, evalLoss: 0.5, outputPath: '/data/models/spectra-domain.gguf',
  error: null, failedPhase: null, evaluationId: 'eval-7',
  createdAt: '2026-08-05T10:00:00Z', completedAt: '2026-08-05T11:00:00Z',
  parameters: { loraRank: 64, loraAlpha: 128, epochs: 3, learningRate: 2e-4, minConfidence: 0.8, valSplit: 0.1 },
};

const createJob = vi.fn(() => Promise.resolve({ data: { jobId: 'job-eval' } }));

vi.mock('../services/api', () => ({
  fineTuningApi: {
    getJobs: vi.fn(() => Promise.resolve({ data: [COMPLETED_JOB] })),
    getJob: vi.fn(() => Promise.resolve({ data: COMPLETED_JOB })),
    getTelemetry: vi.fn(() => Promise.resolve({ data: { logs: [], losses: [] } })),
    createJob: (job: unknown) => createJob(job),
    cancelJob: vi.fn(),
    getBaseModels: vi.fn(() => Promise.resolve({ data: [] })),
    getAvailability: vi.fn(() => Promise.resolve({ data: { available: true, reason: null } })),
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

/** La page contient des <Link> : hors Router, leur rendu échoue avant toute assertion. */
const renderPage = () => render(<MemoryRouter><FineTuning /></MemoryRouter>);

const openForm = async () => {
  renderPage();
  fireEvent.click(await screen.findByRole('button', { name: /new training job/i }));
};

describe('FineTuning — évaluation enchaînée', () => {
  it('laisse la case indisponible tant que l\'export GGUF n\'est pas demandé', async () => {
    await openForm();

    const evaluate = screen.getByRole('checkbox', { name: /evaluate after/i });
    // Désactivée plutôt que masquée : l'utilisateur doit voir que l'option existe, et le titre
    // lui dit à quelle condition.
    expect(evaluate).toBeDisabled();
  });

  it('l\'active dès que l\'export est coché, et la transmet à l\'API', async () => {
    await openForm();

    fireEvent.click(screen.getByRole('checkbox', { name: /export gguf & register/i }));
    const evaluate = screen.getByRole('checkbox', { name: /evaluate after/i });
    expect(evaluate).toBeEnabled();

    fireEvent.click(evaluate);
    fireEvent.click(screen.getByRole('button', { name: /launch training/i }));

    await waitFor(() => expect(createJob).toHaveBeenCalled());
    expect(createJob.mock.calls[0][0]).toMatchObject({ exportGguf: true, autoEvaluate: true });
  });

  it('décocher l\'export retire aussi l\'évaluation, au lieu de laisser un choix mort', async () => {
    // Sans cette remise à zéro, la case restait cochée mais inerte : la requête partait avec
    // autoEvaluate=true et exportGguf=false, et le serveur la refusait — après coup.
    await openForm();

    const exportGguf = screen.getByRole('checkbox', { name: /export gguf & register/i });
    fireEvent.click(exportGguf);
    fireEvent.click(screen.getByRole('checkbox', { name: /evaluate after/i }));
    fireEvent.click(exportGguf);

    fireEvent.click(screen.getByRole('button', { name: /launch training/i }));

    await waitFor(() => expect(createJob).toHaveBeenCalled());
    expect(createJob.mock.calls[0][0]).toMatchObject({ exportGguf: false, autoEvaluate: false });
  });

  it('un job évalué propose d\'aller lire son score', async () => {
    // Un entraînement terminé ne disait rien de la QUALITÉ obtenue : le lien est la seule
    // chose qui relie les deux moitiés de la chaîne côté interface.
    renderPage();
    // Un job terminal n'est pas restauré automatiquement : on le sélectionne dans l'historique.
    fireEvent.click(await screen.findByRole('cell', { name: 'spectra-domain' }));

    expect(await screen.findByRole('link', { name: /view evaluation/i })).toBeInTheDocument();
  });
});
