import { describe, it, expect } from 'vitest';
import {
  RAG_MODULES, appliedModules, overridesFromDisabled, isBm25Only, relevancePct, fmtMs, formatStageCounts,
} from './ragPipeline';
import type { RagMetaFlags, OverrideKey } from './ragPipeline';
import type { StreamStageTrace } from '../services/api';

describe('isBm25Only', () => {
  it('flags a keyword-only chunk (sentinel distance 1.0 with a BM25 score)', () => {
    expect(isBm25Only({ distance: 1.0, bm25Score: 3.2 })).toBe(true);
    expect(isBm25Only({ distance: 0.999, bm25Score: 0.1 })).toBe(true);
  });
  it('does not flag a vector match', () => {
    expect(isBm25Only({ distance: 0.12, bm25Score: 0 })).toBe(false);
    expect(isBm25Only({ distance: 0.12 })).toBe(false);
  });
  it('does not flag distance 1.0 without a BM25 score', () => {
    expect(isBm25Only({ distance: 1.0 })).toBe(false);
    expect(isBm25Only({ distance: 1.0, bm25Score: 0 })).toBe(false);
  });
});

describe('relevancePct', () => {
  it('maps cosine distance to a 0–100 relevance', () => {
    expect(relevancePct({ distance: 0.12 })).toBe(88);
    expect(relevancePct({ distance: 0 })).toBe(100);
    expect(relevancePct({ distance: 1 })).toBe(0);
  });
  it('clamps out-of-range distances', () => {
    expect(relevancePct({ distance: -0.2 })).toBe(100);
    expect(relevancePct({ distance: 1.4 })).toBe(0);
  });
  it('returns null for BM25-only chunks (misleading 0% otherwise)', () => {
    expect(relevancePct({ distance: 1.0, bm25Score: 2.5 })).toBeNull();
  });
  it('returns null when distance is not a number', () => {
    expect(relevancePct({ distance: undefined as unknown as number })).toBeNull();
  });
});

describe('fmtMs', () => {
  it('formats sub-second durations in ms', () => {
    expect(fmtMs(0)).toBe('0ms');
    expect(fmtMs(499.6)).toBe('500ms');
    expect(fmtMs(999)).toBe('999ms');
  });
  it('formats seconds with one decimal under 10s', () => {
    expect(fmtMs(1000)).toBe('1.0s');
    expect(fmtMs(1540)).toBe('1.5s');
    expect(fmtMs(9990)).toBe('10.0s');
  });
  it('drops the decimal at or above 10s', () => {
    expect(fmtMs(12000)).toBe('12s');
    expect(fmtMs(65400)).toBe('65s');
  });
});

describe('overridesFromDisabled', () => {
  it('returns undefined when nothing is disabled (server default)', () => {
    expect(overridesFromDisabled([])).toBeUndefined();
    expect(overridesFromDisabled(new Set<OverrideKey>())).toBeUndefined();
  });
  it('forces each disabled module OFF (never ON)', () => {
    expect(overridesFromDisabled(['rerank', 'corrective'])).toEqual({ rerank: false, corrective: false });
  });
  it('merges an extra module (A/B comparison: session base + compared module)', () => {
    expect(overridesFromDisabled(['rerank'], 'hybrid')).toEqual({ rerank: false, hybrid: false });
  });
  it('deduplicates the extra module already in the set', () => {
    expect(overridesFromDisabled(['rerank'], 'rerank')).toEqual({ rerank: false });
  });
});

describe('appliedModules', () => {
  const base: RagMetaFlags = { ragStrategy: 'STANDARD' };

  it('returns nothing without meta', () => {
    expect(appliedModules(undefined)).toEqual([]);
  });
  it('lists only modules whose flag actually fired', () => {
    const meta: RagMetaFlags = { ...base, rerankApplied: true, hybridSearchApplied: true };
    const keys = appliedModules(meta).map(m => m.key);
    expect(keys).toContain('rerank');
    expect(keys).toContain('hybrid');
    expect(keys).not.toContain('corrective');
    expect(keys).not.toContain('selfRag');
  });
  it('includes adaptive only when the strategy is AGENTIC', () => {
    expect(appliedModules({ ragStrategy: 'STANDARD' }).map(m => m.key)).not.toContain('adaptive');
    expect(appliedModules({ ragStrategy: 'AGENTIC' }).map(m => m.key)).toContain('adaptive');
  });
  it('never invents a module outside the registry', () => {
    const meta: RagMetaFlags = { ragStrategy: 'AGENTIC', rerankApplied: true, correctiveApplied: true };
    const knownKeys = RAG_MODULES.map(m => m.key);
    for (const m of appliedModules(meta)) expect(knownKeys).toContain(m.key);
  });
});

describe('formatStageCounts', () => {
  const trace = (o: Partial<StreamStageTrace>): StreamStageTrace =>
    ({ stage: 'retrieval', durationMs: 10, ...o });

  it('reports iterations for the agentic loop', () => {
    expect(formatStageCounts(trace({ stage: 'agentic', outCount: 2 }))).toBe('2 iter');
  });
  it('reports before→after with the dropped count for filtering steps', () => {
    expect(formatStageCounts(trace({ stage: 'grading', inCount: 5, outCount: 3 }))).toBe('5→3 chunks (−2)');
  });
  it('omits the delta when nothing was dropped', () => {
    expect(formatStageCounts(trace({ stage: 'grading', inCount: 4, outCount: 4 }))).toBe('4→4 chunks');
  });
  it('reports a plain chunk count for retrieval (outCount only)', () => {
    expect(formatStageCounts(trace({ stage: 'retrieval', outCount: 6 }))).toBe('6 chunks');
  });
  it('returns null for steps without counters (routing, generation)', () => {
    expect(formatStageCounts(trace({ stage: 'routing' }))).toBeNull();
    expect(formatStageCounts(trace({ stage: 'generation' }))).toBeNull();
  });
});
