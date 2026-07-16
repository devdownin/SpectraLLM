import type { ReactNode } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import '../i18n';

/**
 * Tests de rendu du Dashboard, centrés sur le câblage introduit par l'audit :
 * les statistiques de commentaires viennent désormais de l'agrégat serveur
 * embarqué dans /api/ged/stats (commentStats) — plus aucun calcul client.
 */

vi.mock('../services/api', () => ({
  datasetApi: {
    getStats: vi.fn().mockResolvedValue({
      data: { totalPairs: 12, chunksInStore: 42, avgConfidence: 0.9, byCategory: { qa: 12 } },
    }),
  },
  gedApi: {
    getStats: vi.fn().mockResolvedValue({
      data: {
        byLifecycle: { INGESTED: 3, QUALIFIED: 1, TRAINED: 0, ARCHIVED: 0 },
        avgQualityScore: 0.8,
        totalChunks: 42,
        // Agrégat serveur (4 COUNT en base) — le Dashboard doit l'utiliser tel quel.
        commentStats: { total: 9, aiGenerated: 7, approved: 5, rejected: 1 },
      },
    }),
  },
  metricsApi: {
    getPersonalization: vi.fn().mockResolvedValue({
      data: {
        approvedComments: 5, rejectedComments: 1, totalAiComments: 7, dpoPairs: 4,
        fineTuningJobs: [], evaluations: [], completedCycles: 0,
        nextTriggerIn: 0, autoRetrainThreshold: 0, completedFineTuningJobs: 0,
        latestEvalScore: 0,
      },
    }),
  },
}));

vi.mock('../hooks/useStatus', () => ({
  useStatus: () => ({
    status: {
      application: 'Spectra', version: 'test', timestamp: new Date().toISOString(),
      services: [
        { name: 'llama-cpp', available: true, details: {} },
        { name: 'llama-cpp-embed', available: true, details: {} },
        { name: 'chromadb', available: true, details: {} },
      ],
    },
    loading: false,
    error: null,
  }),
}));

// Composants graphiques (recharts) et carte de cohérence : hors sujet ici.
vi.mock('../components/charts/LifecycleDonut', () => ({ default: () => null }));
vi.mock('../components/charts/CategoryBar', () => ({ default: () => null }));
vi.mock('../components/EmbeddingConsistencyCard', () => ({ default: () => null }));

import Dashboard from './Dashboard';

function renderDashboard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<Dashboard />, { wrapper });
}

describe('Dashboard', () => {
  it('affiche les stats de commentaires venues de l’agrégat serveur (commentStats)', async () => {
    renderDashboard();
    // Carte « AI Comments » : compteur IA + total, directement depuis commentStats.
    expect(await screen.findByText('7')).toBeInTheDocument();
    expect(screen.getByText('9 total (human + AI)')).toBeInTheDocument();
    // « DPO Ready » dérive de approved > 0 — chiffres exacts du serveur.
    expect(screen.getByText('5 exportable pairs')).toBeInTheDocument();
  });

  it('affiche la santé des services et les stats de la base de connaissances', async () => {
    renderDashboard();
    // « 42 » (chunks) et « 12 » (paires) apparaissent à plusieurs endroits — au moins une fois.
    expect((await screen.findAllByText('42')).length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText(/online/i).length).toBeGreaterThanOrEqual(1);
    expect((await screen.findAllByText('12')).length).toBeGreaterThanOrEqual(1);
  });

  it('propose les actions rapides du pipeline', async () => {
    renderDashboard();
    expect(await screen.findByText('Fine-Tune')).toBeInTheDocument();
    expect(screen.getByText('Ingest')).toBeInTheDocument();
    expect(screen.getByText('Query')).toBeInTheDocument();
  });
});
