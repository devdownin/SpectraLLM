import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import '../i18n';
import i18n from 'i18next';

// ── Mocks ────────────────────────────────────────────────────────────────────
// Le test cible le déclencheur de classification de l'en-tête (R8) : son libellé,
// son état désactivé et l'appel qu'il émet — pas les appels réseau réels.

const bulkClassify = vi.fn(() => Promise.resolve({ data: { taskId: 'task-1' } }));
let classificationConfig: Record<string, unknown> = {};
let gedStats: Record<string, unknown> = {};

vi.mock('../services/api', () => ({
  gedApi: {
    getStats: vi.fn(() => Promise.resolve({ data: gedStats })),
    listDocuments: vi.fn(() => Promise.resolve({
      data: { content: [], page: 0, totalPages: 1, totalElements: 0 },
    })),
    getDocument: vi.fn(() => Promise.resolve({ data: {} })),
    getClassificationConfig: vi.fn(() => Promise.resolve({ data: classificationConfig })),
    bulkClassify: (list: string[] | null, force: boolean) => bulkClassify(list, force),
    classify: vi.fn(() => Promise.resolve({ data: {} })),
    getClassificationTask: vi.fn(() => Promise.resolve({ data: {} })),
    cancelClassificationTask: vi.fn(() => Promise.resolve({ data: {} })),
    updateLifecycle: vi.fn(), deleteDocument: vi.fn(), bulkLifecycle: vi.fn(),
    bulkAddTags: vi.fn(), addTags: vi.fn(), removeTags: vi.fn(),
  },
  commentApi: {
    list: vi.fn(() => Promise.resolve({ data: [] })),
    addHuman: vi.fn(), generate: vi.fn(), rate: vi.fn(),
    delete: vi.fn(), exportDpo: vi.fn(),
  },
}));

vi.mock('sonner', () => ({
  toast: { warning: vi.fn(), error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

import Documents from './Documents';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Documents />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Stats GED avec N documents restant à classifier. */
function statsWithPending(unclassified: number, classified = 10) {
  return {
    total: classified + unclassified,
    byLifecycle: { INGESTED: classified + unclassified },
    totalChunks: 100,
    classification: { classified, unclassified, coverage: 0.5, topCategories: [] },
  };
}

describe('Documents — déclencheur de classification dans l’en-tête', () => {
  beforeEach(() => {
    i18n.changeLanguage('en');
    bulkClassify.mockClear();
    classificationConfig = {
      enabled: true, autoClassify: false, openTaxonomy: false,
      taxonomy: ['procedures'], maxCategories: 3, minConfidence: 0.5, model: 'phi-4-mini',
    };
    gedStats = statsWithPending(23);
  });

  it('affiche le nombre de documents restant à classifier', async () => {
    renderPage();

    // Le libellé porte le reste à faire : on sait quoi ET combien sans descendre la page.
    expect(await screen.findByRole('button', { name: /Classify \(23\)/ })).toBeEnabled();
  });

  it('lance la classification de tout le fonds non classifié au clic', async () => {
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /Classify \(23\)/ }));

    // null = « tous les non classifiés » ; force=false pour ne pas re-payer un LLM
    // sur les documents déjà étiquetés.
    await waitFor(() => expect(bulkClassify).toHaveBeenCalledWith(null, false));
  });

  it('se désactive quand il ne reste rien à classifier', async () => {
    gedStats = statsWithPending(0);
    renderPage();

    const button = await screen.findByRole('button', { name: /All classified/ });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(bulkClassify).not.toHaveBeenCalled();
  });

  it('disparaît quand le classifieur est désactivé côté serveur', async () => {
    classificationConfig = { ...classificationConfig, enabled: false };
    renderPage();

    // On attend que la page soit rendue avant de conclure à l'absence du bouton.
    await screen.findByRole('button', { name: /Sync/ });
    expect(screen.queryByRole('button', { name: /Classify/ })).toBeNull();
  });

  it('compte les documents du fonds entier, pas seulement la page chargée', async () => {
    // listDocuments ne renvoie aucun document ; le compteur vient bien des stats serveur.
    gedStats = statsWithPending(412, 88);
    renderPage();

    expect(await screen.findByRole('button', { name: /Classify \(412\)/ })).toBeInTheDocument();
  });
});
