import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RagTracePanel from './RagTracePanel';
import type { Message } from './ragTypes';

const baseMsg: Message = {
  role: 'assistant',
  content: 'Answer [1].',
  status: 'SENT',
  metrics: { ttftMs: 100, totalMs: 800, tokens: 40 },
  sources: [
    { sourceFile: 'config.md', distance: 0.12, text: 'La valeur par défaut est 512.', rerankScore: 0.91 },
  ],
  ragMeta: {
    conversationalApplied: false, correctiveApplied: true, selfRagApplied: false,
    ragStrategy: 'STANDARD', rerankApplied: true, hybridSearchApplied: true,
    multiQueryApplied: false, compressionApplied: false, semanticDedupApplied: false,
    longContextApplied: false, chunkCount: 6, contextChars: 4000,
    stages: [
      { stage: 'retrieval', durationMs: 120, inCount: undefined, outCount: 10, detail: undefined },
      { stage: 'grading', durationMs: 60, inCount: 10, outCount: 6, detail: undefined },
      { stage: 'generation', durationMs: 300, inCount: undefined, outCount: undefined, detail: undefined },
    ],
  },
};

describe('RagTracePanel', () => {
  it('renders the strategy, timeline, funnel, token budget and final sources', () => {
    render(<RagTracePanel traceMsg={baseMsg} onClose={() => {}} />);
    expect(screen.getByText('Algorithm Trace')).toBeInTheDocument();
    expect(screen.getByText('STANDARD')).toBeInTheDocument();
    expect(screen.getByText('Pipeline Timeline')).toBeInTheDocument();
    // Entonnoir : retrieval → after Corrective (6, −4 dropped)
    expect(screen.getByText('Retrieval Funnel')).toBeInTheDocument();
    expect(screen.getByText('After Corrective')).toBeInTheDocument();
    expect(screen.getByText('−4 dropped')).toBeInTheDocument();
    expect(screen.getByText('Token Budget')).toBeInTheDocument();
    expect(screen.getByText('Optimizations Triggered')).toBeInTheDocument();
    // Contexte final : la source et son score de rerank.
    expect(screen.getByText('config.md')).toBeInTheDocument();
    expect(screen.getByText('rr 0.91')).toBeInTheDocument();
  });

  it('calls onClose when the close button is clicked', async () => {
    const onClose = vi.fn();
    render(<RagTracePanel traceMsg={baseMsg} onClose={onClose} />);
    await userEvent.click(screen.getByLabelText('Close trace panel'));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('omits the funnel when no filtering stage ran', () => {
    const noFunnel: Message = {
      ...baseMsg,
      ragMeta: { ...baseMsg.ragMeta!, stages: [
        { stage: 'retrieval', durationMs: 100, inCount: undefined, outCount: 5, detail: undefined },
        { stage: 'generation', durationMs: 200, inCount: undefined, outCount: undefined, detail: undefined },
      ] },
    };
    render(<RagTracePanel traceMsg={noFunnel} onClose={() => {}} />);
    expect(screen.queryByText('Retrieval Funnel')).toBeNull();
  });
});
