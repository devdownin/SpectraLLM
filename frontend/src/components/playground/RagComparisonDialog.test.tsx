import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import RagComparisonDialog from './RagComparisonDialog';
import type { Message } from './ragTypes';
import type { ModuleDef } from '../../lib/ragPipeline';

// Stream déterministe : sources → token → done. Évite tout appel réseau réel.
vi.mock('../../services/api', async (orig) => {
  const actual = await orig<typeof import('../../services/api')>();
  return {
    ...actual,
    queryApi: {
      ...actual.queryApi,
      queryStream: () => (async function* () {
        yield { type: 'sources', data: '[]' };
        yield { type: 'token', data: 'Variant answer.' };
        yield { type: 'done', data: JSON.stringify({ ragStrategy: 'STANDARD', hybridSearchApplied: true, chunkCount: 3 }) };
      })(),
    },
    dpoApi: { ...actual.dpoApi, recordPreference: vi.fn().mockResolvedValue({ data: {} }) },
  };
});

const baseline: Message = {
  role: 'assistant',
  content: 'Baseline answer.',
  status: 'SENT',
  sources: [{ sourceFile: 'doc.md', distance: 0.1 }],
  ragMeta: {
    conversationalApplied: false, correctiveApplied: false, selfRagApplied: false,
    ragStrategy: 'STANDARD', rerankApplied: true, hybridSearchApplied: true,
    multiQueryApplied: false, compressionApplied: false, semanticDedupApplied: false,
    longContextApplied: false, chunkCount: 4,
  },
};

const module_: ModuleDef = { key: 'rerank', label: 'Cross-Encoder', flag: 'rerankApplied', hint: '' };

describe('RagComparisonDialog', () => {
  it('renders the baseline and streams the variant, then offers the preference vote', async () => {
    render(
      <RagComparisonDialog
        baseline={baseline}
        question="Quelle est la valeur par défaut ?"
        module={module_}
        history={[]}
        temperature={0.7}
        topP={0.9}
        topCandidates={20}
        ragEnabled
        onClose={() => {}}
      />,
    );
    // En-tête + réponse de référence rendus de façon synchrone.
    expect(screen.getByText('A/B — without Cross-Encoder')).toBeInTheDocument();
    expect(screen.getByText('Baseline answer.')).toBeInTheDocument();
    // La variante streamée apparaît, puis le vote de préférence.
    expect(await screen.findByText('Variant answer.')).toBeInTheDocument();
    expect(await screen.findByText('Which is better?')).toBeInTheDocument();
  });
});
