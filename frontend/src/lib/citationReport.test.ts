import { describe, it, expect } from 'vitest';
import type { CitationReport, CitationSentenceCheck } from '../services/api';
import { citationMarks, summarizeCitations } from './citationReport';

const check = (status: CitationSentenceCheck['status'], citations: number[]): CitationSentenceCheck => ({
  sentence: `phrase ${citations.join(',')}`, citations, bestScore: status === 'INVALID_CITATION' ? null : 0.5, status,
});

const report = (checks: CitationSentenceCheck[], extra: Partial<CitationReport> = {}): CitationReport => {
  const supported = checks.filter(c => c.status === 'SUPPORTED').length;
  return {
    citedSentences: checks.length, supportedSentences: supported, unsupportedSentences: checks.length - supported,
    invalidCitations: 0, uncitedSentences: 0, faithfulness: checks.length ? supported / checks.length : null,
    minScore: 0.2, checks, ...extra,
  };
};

describe('summarizeCitations', () => {
  it('ne dit rien sans rapport ni phrase citée', () => {
    expect(summarizeCitations(null)).toBeNull();
    expect(summarizeCitations(undefined)).toBeNull();
    expect(summarizeCitations(report([]))).toBeNull();
  });

  it('good quand tout est étayé', () => {
    expect(summarizeCitations(report([check('SUPPORTED', [1]), check('SUPPORTED', [2])])))
      .toEqual({ level: 'good', supported: 2, cited: 2, flagged: 0, invalid: 0 });
  });

  it('warn quand au moins la moitié l\'est, bad en dessous', () => {
    expect(summarizeCitations(report([check('SUPPORTED', [1]), check('UNSUPPORTED', [2])]))?.level).toBe('warn');
    expect(summarizeCitations(report([check('SUPPORTED', [1]), check('UNSUPPORTED', [2]), check('UNSUPPORTED', [3])]))?.level)
      .toBe('bad');
  });

  it('compte une citation invalide parmi les phrases à examiner', () => {
    const s = summarizeCitations(report([check('SUPPORTED', [1]), check('INVALID_CITATION', [9])], { invalidCitations: 1 }));
    expect(s).toMatchObject({ flagged: 1, invalid: 1, level: 'warn' });
  });
});

describe('citationMarks', () => {
  it('un numéro est étayé dès qu\'une phrase qui le cite l\'est', () => {
    const marks = citationMarks(report([check('UNSUPPORTED', [1]), check('SUPPORTED', [1, 2]), check('UNSUPPORTED', [3])]), 3);
    expect(marks).toEqual({ 1: 'supported', 2: 'supported', 3: 'unsupported' });
  });

  it('ignore les numéros hors plage et les rapports absents', () => {
    expect(citationMarks(report([check('UNSUPPORTED', [7, 0])]), 3)).toEqual({});
    expect(citationMarks(null, 3)).toEqual({});
  });
});
