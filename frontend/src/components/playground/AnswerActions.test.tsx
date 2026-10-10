import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import i18n from '../../i18n';
import AnswerActions from './AnswerActions';
import type { Message } from './ragTypes';
beforeEach(async () => { await i18n.changeLanguage('en'); });
const actions = () => ({ onSources: vi.fn(), onTrace: vi.fn(), onCompare: vi.fn(), onEvaluate: vi.fn() });
it('discloses missing evidence and interruption, disabling impossible actions', () => {
 render(<AnswerActions message={{role: 'assistant', content: 'Partial', stopped: true}} {...actions()} />);
 expect(screen.getByText(/Generation stopped/)).toBeInTheDocument();
 expect(screen.getByText(/No document sources/)).toBeInTheDocument();
 for (const name of ['Inspect sources','View RAG trace','Compare a RAG module']) expect(screen.getByRole('button',{name})).toBeDisabled();
 expect(screen.getByText(/not automatically scored/)).toBeInTheDocument();
});
it('opens sources, existing trace, module comparison and evaluation separately', async () => {
 const callbacks = actions();
 const message: Message = { role:'assistant',content:'Answer',sources:[{sourceFile:'test.pdf',distance:0.2}],ragMeta: { ragStrategy:'AGENTIC',agenticStopReason:'MAX_ITERATIONS',hybridSearchApplied:true } as Message['ragMeta'] };
 render(<AnswerActions message={message} {...callbacks} />);
 expect(screen.getByText(/iteration limit/)).toBeInTheDocument();
 const user=userEvent.setup();
 for (const [name, callback] of [['Inspect sources',callbacks.onSources],['View RAG trace',callbacks.onTrace],['Compare a RAG module',callbacks.onCompare],['Open evaluations',callbacks.onEvaluate]] as const) {
  await user.click(screen.getByRole('button',{name})); expect(callback).toHaveBeenCalledOnce();
 }
});
