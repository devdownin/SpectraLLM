import { describe, expect, it } from 'vitest';
import type { BenchmarkEntry } from '../types/api';
import {
  DEFAULT_GRID, MAX_ARMS, armCount, draftToEntries, envSnippet, invalidEntries, outOfCorpusEntry,
  toCalibrationBody, unanswerableShare,
} from './calibration';

const entry = (over: Partial<BenchmarkEntry> = {}): BenchmarkEntry =>
  ({ question: 'Q ?', reference: 'R', category: 'general', answerable: true, expectedSources: ['a.pdf'], ...over });

describe('grid', () => {
  it('counts a setting left to the configuration as one value', () => {
    expect(armCount({ rerankMinScores: [], neighborWindows: [], dedupStrategies: [] })).toBe(1);
    expect(armCount(DEFAULT_GRID)).toBe(3);
    expect(armCount({ rerankMinScores: [0, 0.1, 0.2], neighborWindows: [0, 1], dedupStrategies: ['none', 'mmr'] })).toBe(12);
  });

  it('flags a grid over the server cap', () => {
    const grid = { rerankMinScores: [0, 0.1, 0.2, 0.3, 0.5], neighborWindows: [0, 1, 2], dedupStrategies: ['none', 'mmr'] };
    expect(armCount(grid)).toBeGreaterThan(MAX_ARMS);
  });

  it('builds a body with ordered values and only known strategies', () => {
    expect(toCalibrationBody({ rerankMinScores: [0.3, 0], neighborWindows: [2, 1], dedupStrategies: ['mmr', 'bogus', 'none'] }, 5, 2))
      .toEqual({ rerankMinScores: [0, 0.3], neighborWindows: [1, 2], dedupStrategies: ['none', 'mmr'], maxContextChunks: 5, runs: 2 });
  });
});

describe('curated benchmark', () => {
  it('measures the share of out-of-corpus questions', () => {
    expect(unanswerableShare([])).toBe(0);
    expect(unanswerableShare([entry(), entry(), entry(), entry({ answerable: false, reference: null })])).toBe(0.25);
  });

  it('flags what the server would refuse', () => {
    expect(invalidEntries([entry(), entry({ question: '  ' }), entry({ reference: ' ' }), entry({ reference: null }),
      entry({ answerable: false, reference: null })])).toEqual([1, 2, 3]);
    expect(invalidEntries([outOfCorpusEntry()])).toEqual([0]);
  });

  it('turns a server draft into editable entries', () => {
    expect(draftToEntries([{ question: 'Q ?', reference: 'R', category: 'rh', answerable: true, expectedSources: ['a.pdf'], draft: true }]))
      .toEqual([{ question: 'Q ?', reference: 'R', category: 'rh', answerable: true, expectedSources: ['a.pdf'] }]);
  });
});

describe('envSnippet', () => {
  const texts = { header: 'Calibrated', dedupThreshold: 'Keep SPECTRA_SEMANTIC_DEDUP_THRESHOLD' };

  it('writes only the settings the arm measured', () => {
    expect(envSnippet({ rerankMinScore: 0.2, neighborWindow: null, dedupStrategy: null }, texts))
      .toBe('# Calibrated\nSPECTRA_RERANKER_MIN_SCORE=0.2');
    expect(envSnippet({ rerankMinScore: 0, neighborWindow: 1 }, texts))
      .toBe('# Calibrated\nSPECTRA_RERANKER_MIN_SCORE=0\nSPECTRA_RAG_NEIGHBOR_WINDOW=1');
  });

  it('maps deduplication to the switch and the strategy', () => {
    expect(envSnippet({ dedupStrategy: 'none' }, texts)).toBe('# Calibrated\nSPECTRA_SEMANTIC_DEDUP_ENABLED=false');
    expect(envSnippet({ dedupStrategy: 'mmr' }, texts)).toBe(
      '# Calibrated\nSPECTRA_SEMANTIC_DEDUP_ENABLED=true\nSPECTRA_SEMANTIC_DEDUP_STRATEGY=mmr\n# Keep SPECTRA_SEMANTIC_DEDUP_THRESHOLD');
  });

  it('is empty when the arm changed nothing', () => {
    expect(envSnippet(null, texts)).toBe('');
    expect(envSnippet({}, texts)).toBe('');
  });
});
