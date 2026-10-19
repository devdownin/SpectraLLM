import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../i18n';
import CitationReportBadge from './CitationReportBadge';
import type { CitationReport } from '../../services/api';

beforeEach(async () => { await i18n.changeLanguage('en'); });

const report: CitationReport = {
  citedSentences: 3, supportedSentences: 1, unsupportedSentences: 2, invalidCitations: 1, uncitedSentences: 0,
  faithfulness: 1 / 3, minScore: 0.2,
  checks: [
    { sentence: 'La garantie dure deux ans [1].', citations: [1], bestScore: 0.91, status: 'SUPPORTED' },
    { sentence: 'Le prix est de 10 € [2].', citations: [2], bestScore: 0.04, status: 'UNSUPPORTED' },
    { sentence: 'Voir aussi la notice [9].', citations: [9], bestScore: null, status: 'INVALID_CITATION' },
  ],
};

describe('CitationReportBadge', () => {
  it('renders nothing without a report or without any cited sentence', () => {
    const { container, rerender } = render(<CitationReportBadge report={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<CitationReportBadge report={{ ...report, citedSentences: 0, checks: [] }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('summarises the check and expands to a verdict per sentence, with the caveat', async () => {
    render(<CitationReportBadge report={report} />);
    const badge = screen.getByRole('button', { name: /1\/3 sentences backed/ });
    expect(badge).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText(/Le prix est de 10/)).toBeNull();

    await userEvent.click(badge);

    expect(badge).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(/Le prix est de 10/)).toBeInTheDocument();
    expect(screen.getByText('score 0.04 (threshold 0.20)')).toBeInTheDocument();
    expect(screen.getByText('Cites a source that does not exist')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Not supported by the cited passage' })).toBeInTheDocument();
    expect(screen.getByText(/relevant to the sentence, not whether it logically proves it/)).toBeInTheDocument();
  });

  it('lets a reachable citation open its source, but not one beyond the sources served', async () => {
    const onJump = vi.fn();
    render(<CitationReportBadge report={report} onJumpToSource={onJump} sourceCount={2} />);
    await userEvent.click(screen.getByRole('button', { name: /sentences backed/ }));

    await userEvent.click(screen.getByRole('button', { name: 'Open source 2' }));

    expect(onJump).toHaveBeenCalledWith(2);
    expect(screen.queryByRole('button', { name: 'Open source 9' })).toBeNull();
  });
});
