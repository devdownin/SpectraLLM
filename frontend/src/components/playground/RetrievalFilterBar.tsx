import { useId, useState } from 'react';
import type { FC } from 'react';
import { useTranslation } from 'react-i18next';
import { EMPTY_FILTER, isFilterActive } from '../../lib/retrievalFilter';
import type { DocumentFacets, FilterState } from '../../lib/retrievalFilter';

const MAX_SOURCES_SHOWN = 8;

const toggle = (list: string[], value: string): string[] =>
  list.includes(value) ? list.filter(v => v !== value) : [...list, value];

/**
 * Filtre de recherche du Playground : restreint les documents interrogés (catégories GED,
 * documents, période d'ingestion). Replié, il ne montre que ce qu'il restreint — une puce par
 * critère, retirable — pour qu'on ne pose pas une question en oubliant qu'elle est limitée.
 */
const RetrievalFilterBar: FC<{
  value: FilterState;
  onChange: (next: FilterState) => void;
  facets: DocumentFacets | undefined;
  /** La liste des documents proposés est incomplète (plus de documents que ceux chargés). */
  truncated?: boolean;
  loading?: boolean;
  disabled?: boolean;
}> = ({ value, onChange, facets, truncated = false, loading = false, disabled = false }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [sourceSearch, setSourceSearch] = useState('');
  const panelId = useId();
  const active = isFilterActive(value);

  const matchingSources = (facets?.sources ?? [])
    .filter(s => s.name.toLowerCase().includes(sourceSearch.trim().toLowerCase()));
  // Les documents déjà choisis passent en tête de la liste (sans quoi un choix tardif dans
  // l'ordre alphabétique tomberait sous la coupe) ; une recherche qui ne les retient plus les
  // masque de la liste, mais leur puce reste visible au-dessus.
  const shownSources = [
    ...matchingSources.filter(s => value.sources.includes(s.name)),
    ...matchingSources.filter(s => !value.sources.includes(s.name)),
  ].slice(0, MAX_SOURCES_SHOWN);

  const chip = (key: string, label: string, onRemove: () => void) => (
    <span key={key} className="inline-flex items-center gap-1 border border-primary/40 bg-primary/10 text-primary px-2 py-0.5 text-[11px] font-bold">
      {label}
      <button
        type="button"
        onClick={onRemove}
        aria-label={t('playground.filter.remove', { label })}
        className="material-symbols-outlined text-[13px] hover:text-error"
      >close</button>
    </span>
  );

  return (
    <div className="mb-3" data-testid="retrieval-filter">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => setOpen(o => !o)}
          aria-expanded={open}
          aria-controls={panelId}
          disabled={disabled}
          className={`inline-flex items-center gap-1.5 border px-2 py-1 text-[11px] uppercase tracking-widest transition-colors disabled:opacity-40 ${active ? 'border-primary/50 text-primary' : 'border-outline-variant/30 text-outline hover:text-primary'}`}
        >
          <span aria-hidden="true" className="material-symbols-outlined text-[14px]">filter_alt</span>
          {active ? t('playground.filter.active') : t('playground.filter.label')}
        </button>
        {value.categories.map(c => chip(`c-${c}`, c, () => onChange({ ...value, categories: value.categories.filter(x => x !== c) })))}
        {value.sources.map(s => chip(`s-${s}`, s, () => onChange({ ...value, sources: value.sources.filter(x => x !== s) })))}
        {value.from && chip('from', t('playground.filter.sinceChip', { date: value.from }), () => onChange({ ...value, from: '' }))}
        {value.to && chip('to', t('playground.filter.untilChip', { date: value.to }), () => onChange({ ...value, to: '' }))}
        {active && (
          <button type="button" onClick={() => onChange(EMPTY_FILTER)} className="text-[11px] text-outline hover:text-error underline underline-offset-2">
            {t('playground.filter.clear')}
          </button>
        )}
      </div>

      {open && (
        <div id={panelId} className="mt-2 border border-outline-variant/20 bg-surface-container-lowest p-3 space-y-3">
          <p className="text-[11px] text-on-surface-variant">{t('playground.filter.hint')}</p>

          <fieldset>
            <legend className="font-label text-[10px] uppercase tracking-widest text-outline mb-1">{t('playground.filter.categories')}</legend>
            {loading && !facets
              ? <p className="text-[11px] text-outline">{t('playground.filter.loading')}</p>
              : facets && facets.categories.length > 0
                ? (
                  <div className="flex flex-wrap gap-1.5">
                    {facets.categories.map(c => (
                      <button
                        key={c.name}
                        type="button"
                        aria-pressed={value.categories.includes(c.name)}
                        onClick={() => onChange({ ...value, categories: toggle(value.categories, c.name) })}
                        className={`border px-2 py-0.5 text-[11px] ${value.categories.includes(c.name) ? 'border-primary bg-primary/10 text-primary font-bold' : 'border-outline-variant/30 text-on-surface-variant hover:border-primary/50'}`}
                      >{c.name} <span className="text-outline">({c.count})</span></button>
                    ))}
                  </div>
                )
                : <p className="text-[11px] text-outline">{t('playground.filter.noCategories')}</p>}
          </fieldset>

          <fieldset>
            <legend className="font-label text-[10px] uppercase tracking-widest text-outline mb-1">{t('playground.filter.documents')}</legend>
            <input
              type="search"
              value={sourceSearch}
              onChange={e => setSourceSearch(e.target.value)}
              aria-label={t('playground.filter.searchDocuments')}
              placeholder={t('playground.filter.searchDocuments')}
              className="w-full bg-surface-container border border-outline-variant/20 px-2 py-1 text-xs mb-1.5"
            />
            <ul className="space-y-0.5">
              {shownSources.map(s => (
                <li key={s.name}>
                  <label className="flex items-center gap-2 text-[12px] text-on-surface cursor-pointer">
                    <input
                      type="checkbox"
                      checked={value.sources.includes(s.name)}
                      onChange={() => onChange({ ...value, sources: toggle(value.sources, s.name) })}
                    />
                    <span className="truncate">{s.name}</span>
                  </label>
                </li>
              ))}
            </ul>
            {matchingSources.length > shownSources.length && (
              <p className="text-[11px] text-outline mt-1">{t('playground.filter.moreDocuments', { count: matchingSources.length - shownSources.length })}</p>
            )}
            {truncated && <p className="text-[11px] text-secondary mt-1">{t('playground.filter.truncated')}</p>}
          </fieldset>

          <fieldset className="flex flex-wrap items-end gap-3">
            <legend className="font-label text-[10px] uppercase tracking-widest text-outline mb-1">{t('playground.filter.period')}</legend>
            <label className="text-[11px] text-on-surface-variant">
              {t('playground.filter.from')}
              <input type="date" value={value.from} max={value.to || undefined}
                onChange={e => onChange({ ...value, from: e.target.value })}
                className="block mt-0.5 bg-surface-container border border-outline-variant/20 px-2 py-1 text-xs text-on-surface" />
            </label>
            <label className="text-[11px] text-on-surface-variant">
              {t('playground.filter.to')}
              <input type="date" value={value.to} min={value.from || undefined}
                onChange={e => onChange({ ...value, to: e.target.value })}
                className="block mt-0.5 bg-surface-container border border-outline-variant/20 px-2 py-1 text-xs text-on-surface" />
            </label>
          </fieldset>
        </div>
      )}
    </div>
  );
};

export default RetrievalFilterBar;
