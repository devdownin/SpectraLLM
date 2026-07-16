import type { ReactNode } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { GlobalTask } from '../hooks/useGlobalTasks';
import '../i18n';

/**
 * Tests de rendu de la page Ingestion, centrés sur la refonte de l'audit :
 * le flux « live » et le panneau de génération sont désormais dérivés du suivi
 * global des tâches (useGlobalTasks) — plus aucun poller setInterval local.
 */

vi.mock('../services/api', () => ({
  ingestApi: {
    getHistory: vi.fn().mockResolvedValue({ data: { content: [], totalElements: 0, number: 0 } }),
    uploadFile: vi.fn(),
    ingestUrls: vi.fn(),
  },
  datasetApi: {
    getStats: vi.fn().mockResolvedValue({
      data: { totalPairs: 12, chunksInStore: 42, avgConfidence: 0.9, byCategory: { qa: 12 } },
    }),
    generateDataset: vi.fn(),
  },
}));

const task = (overrides: Partial<GlobalTask>): GlobalTask => ({
  id: 'ingestion:x', kind: 'ingestion', icon: 'cloud_upload', label: 'x', detail: null,
  status: 'running', progress: null, path: '/ingestion', error: null,
  timestamp: '2026-07-16T05:00:00Z', startedAt: '2026-07-16T05:00:00Z', raw: {},
  ...overrides,
});

// Suivi global simulé : une ingestion en cours + une génération terminée.
vi.mock('../hooks/useGlobalTasks', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../hooks/useGlobalTasks')>();
  return {
    ...actual,
    useGlobalTasks: () => ({
      tasks: [
        task({
          id: 'ingestion:t1', label: 'rapport-annuel.pdf', status: 'running', progress: 0.5,
          raw: { status: 'PROCESSING', chunksCreated: 5, chunksExpected: 10 },
        }),
        task({
          id: 'dataset:g1', kind: 'dataset', status: 'completed', progress: 1,
          raw: { status: 'COMPLETED', pairsGenerated: 12, chunksProcessed: 10, totalChunks: 10 },
        }),
      ],
      activeTasks: [], activeCount: 1, isLoading: false, liveStatus: 'open',
    }),
  };
});

import Ingestion from './Ingestion';

function renderIngestion() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<Ingestion />, { wrapper });
}

describe('Ingestion', () => {
  it('affiche l’ingestion en cours issue du suivi global (fichier + progression)', async () => {
    renderIngestion();
    expect(await screen.findByText('rapport-annuel.pdf')).toBeInTheDocument();
    expect(screen.getByText('5/10 chunks')).toBeInTheDocument();
  });

  it('affiche la dernière génération (terminée) depuis le suivi global', async () => {
    renderIngestion();
    expect(await screen.findByText('COMPLETED')).toBeInTheDocument();
    // Paires générées + total de chunks du panneau de génération.
    expect(screen.getByText('Pairs Generated')).toBeInTheDocument();
  });

  it('affiche les stats de la base et les presets de taille de corpus', async () => {
    renderIngestion();
    // « 42 » apparaît dans le bandeau, l'entête d'étape et les stats — au moins une fois.
    expect((await screen.findAllByText('42')).length).toBeGreaterThanOrEqual(1);
    // Presets remplaçant l'ancien slider (constat #6) : « tout le corpus » nommé explicitement.
    expect(screen.getByText('full corpus')).toBeInTheDocument();
    expect(screen.getByText('quick test')).toBeInTheDocument();
    // Ordre de grandeur du travail affiché (10 = preset par défaut).
    expect(screen.getByText(/≈ 10 chunks processed/)).toBeInTheDocument();
  });
});
