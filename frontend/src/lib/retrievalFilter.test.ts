import { beforeEach, describe, expect, it } from 'vitest';
import { EMPTY_FILTER, facetsFromDocuments, isFilterActive, loadFilter, saveFilter, toFilterDto } from './retrievalFilter';

beforeEach(() => sessionStorage.clear());

describe('toFilterDto', () => {
  it('returns nothing for an empty filter', () => {
    expect(toFilterDto(EMPTY_FILTER)).toBeUndefined();
    expect(isFilterActive(EMPTY_FILTER)).toBe(false);
  });

  it('trims and deduplicates categories and sources', () => {
    expect(toFilterDto({ ...EMPTY_FILTER, categories: [' juridique ', 'juridique', ''], sources: ['a.pdf', 'a.pdf'] }))
      .toEqual({ categories: ['juridique'], sources: ['a.pdf'] });
  });

  it('turns the last day into an exclusive upper bound, so that day is included', () => {
    expect(toFilterDto({ ...EMPTY_FILTER, from: '2026-01-01', to: '2026-06-30' })).toEqual({
      ingestedAfter: '2026-01-01T00:00:00.000Z',
      ingestedBefore: '2026-07-01T00:00:00.000Z',
    });
    expect(toFilterDto({ ...EMPTY_FILTER, to: '2026-12-31' })?.ingestedBefore).toBe('2027-01-01T00:00:00.000Z');
  });

  it('ignores unreadable or impossible days instead of sending them', () => {
    expect(toFilterDto({ ...EMPTY_FILTER, from: '01/01/2026', to: '2026-02-31' })).toBeUndefined();
  });
});

describe('persistence', () => {
  it('round-trips an active filter and drops it once emptied', () => {
    const active = { ...EMPTY_FILTER, categories: ['rh'], from: '2026-03-01' };
    saveFilter(active);
    expect(loadFilter()).toEqual(active);
    saveFilter(EMPTY_FILTER);
    expect(sessionStorage.getItem('spectra_filter')).toBeNull();
    expect(loadFilter()).toEqual(EMPTY_FILTER);
  });

  it('survives corrupted storage', () => {
    sessionStorage.setItem('spectra_filter', '{not json');
    expect(loadFilter()).toEqual(EMPTY_FILTER);
    sessionStorage.setItem('spectra_filter', JSON.stringify({ categories: 'rh', sources: [1, 'a.pdf'], from: 3 }));
    expect(loadFilter()).toEqual({ categories: [], sources: ['a.pdf'], from: '', to: '' });
  });
});

describe('facetsFromDocuments', () => {
  it('counts categories and documents, most frequent first', () => {
    const facets = facetsFromDocuments([
      { fileName: 'b.pdf', categories: ['rh', 'juridique'] },
      { fileName: 'a.pdf', categories: ['juridique'] },
      { fileName: 'a.pdf' },
    ]);
    expect(facets.categories).toEqual([{ name: 'juridique', count: 2 }, { name: 'rh', count: 1 }]);
    expect(facets.sources).toEqual([{ name: 'a.pdf', count: 2 }, { name: 'b.pdf', count: 1 }]);
  });
});
