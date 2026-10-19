import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../i18n';
import RetrievalFilterBar from './RetrievalFilterBar';
import { EMPTY_FILTER } from '../../lib/retrievalFilter';
import type { DocumentFacets, FilterState } from '../../lib/retrievalFilter';

beforeEach(async () => { await i18n.changeLanguage('en'); });

const facets: DocumentFacets = {
  categories: [{ name: 'juridique', count: 3 }, { name: 'rh', count: 1 }],
  sources: Array.from({ length: 10 }, (_, i) => ({ name: `doc-${i}.pdf`, count: 1 })),
};

const setup = (value: FilterState = EMPTY_FILTER, extra: Partial<React.ComponentProps<typeof RetrievalFilterBar>> = {}) => {
  const onChange = vi.fn();
  render(<RetrievalFilterBar value={value} onChange={onChange} facets={facets} {...extra} />);
  return onChange;
};

describe('RetrievalFilterBar', () => {
  it('stays collapsed and silent while nothing restricts the search', () => {
    setup();
    expect(screen.getByRole('button', { name: 'Filter' })).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
  });

  it('keeps every criterion visible as a removable chip, even collapsed', async () => {
    const value: FilterState = { categories: ['juridique'], sources: ['doc-1.pdf'], from: '2026-01-01', to: '' };
    const onChange = setup(value);

    expect(screen.getByRole('button', { name: 'Filter active' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Remove juridique' }));
    expect(onChange).toHaveBeenLastCalledWith({ ...value, categories: [] });
    await userEvent.click(screen.getByRole('button', { name: 'Remove since 2026-01-01' }));
    expect(onChange).toHaveBeenLastCalledWith({ ...value, from: '' });
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));
    expect(onChange).toHaveBeenLastCalledWith(EMPTY_FILTER);
  });

  it('toggles categories and documents from the panel', async () => {
    const onChange = setup();
    await userEvent.click(screen.getByRole('button', { name: 'Filter' }));

    await userEvent.click(screen.getByRole('button', { name: /juridique/ }));
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_FILTER, categories: ['juridique'] });
    await userEvent.click(screen.getByRole('checkbox', { name: 'doc-2.pdf' }));
    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_FILTER, sources: ['doc-2.pdf'] });
  });

  it('lists a few documents with the chosen ones first, and narrows them by search', async () => {
    setup({ ...EMPTY_FILTER, sources: ['doc-9.pdf'] });
    await userEvent.click(screen.getByRole('button', { name: 'Filter active' }));

    expect(screen.getAllByRole('checkbox')).toHaveLength(8);
    expect(screen.getByRole('checkbox', { name: 'doc-9.pdf' })).toBeChecked();
    expect(screen.getAllByRole('checkbox')[0]).toBeChecked();
    expect(screen.getByText('+ 2 more documents — refine the search')).toBeInTheDocument();

    await userEvent.type(screen.getByRole('searchbox'), 'doc-3');
    expect(screen.getAllByRole('checkbox').map(c => (c as HTMLInputElement).checked)).toEqual([false]);
    // Le document déjà choisi reste visible, en puce, même quand la recherche ne le liste plus.
    expect(screen.getByRole('button', { name: 'Remove doc-9.pdf' })).toBeInTheDocument();
  });

  it('warns when the document list is incomplete, and when there is no category', async () => {
    setup(EMPTY_FILTER, { truncated: true, facets: { categories: [], sources: facets.sources } });
    await userEvent.click(screen.getByRole('button', { name: 'Filter' }));
    expect(screen.getByText(/only the most recent documents/i)).toBeInTheDocument();
    expect(screen.getByText(/No category yet/)).toBeInTheDocument();
  });

  it('sets the ingestion period', async () => {
    const onChange = setup();
    await userEvent.click(screen.getByRole('button', { name: 'Filter' }));

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-03-01' } });

    expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY_FILTER, from: '2026-03-01' });
  });
});
