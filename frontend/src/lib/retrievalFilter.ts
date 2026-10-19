import type { RetrievalFilterDto } from '../services/api';

/**
 * Filtre de recherche du Playground (catégories GED, documents, période d'ingestion) — logique
 * pure, sans React. Le serveur l'applique AVANT le classement, aux index vectoriel et lexical.
 */

export interface FilterState {
  categories: string[];
  /** Noms de fichier des documents, tels que la GED les affiche. */
  sources: string[];
  /** Jour `AAAA-MM-JJ` (UTC) à partir duquel les documents ont été ingérés, borne incluse ; vide = sans borne. */
  from: string;
  /** Dernier jour `AAAA-MM-JJ` (UTC) d'ingestion, borne incluse ; vide = sans borne. */
  to: string;
}

export const EMPTY_FILTER: FilterState = { categories: [], sources: [], from: '', to: '' };

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Jour valide au format `AAAA-MM-JJ`, sinon `null` (31 février compris). */
const parseDay = (value: string): Date | null => {
  const m = DAY.exec(value);
  if (!m) return null;
  const date = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return date.toISOString().startsWith(value) ? date : null;
};

const clean = (values: string[]): string[] => [...new Set(values.map(v => v.trim()).filter(Boolean))];

/** Le filtre restreint-il quoi que ce soit ? Une date illisible ne compte pas : elle n'est pas envoyée. */
export const isFilterActive = (filter: FilterState): boolean => toFilterDto(filter) !== undefined;

/**
 * Corps `filter` de la requête, ou `undefined` si rien n'est à restreindre. La borne haute est
 * le LENDEMAIN à 00:00 UTC : le serveur l'exclut, donc « jusqu'au 30/06 » inclut bien le 30.
 */
export const toFilterDto = (filter: FilterState): RetrievalFilterDto | undefined => {
  const dto: RetrievalFilterDto = {};
  const categories = clean(filter.categories);
  const sources = clean(filter.sources);
  if (categories.length) dto.categories = categories;
  if (sources.length) dto.sources = sources;
  const from = parseDay(filter.from);
  if (from) dto.ingestedAfter = from.toISOString();
  const to = parseDay(filter.to);
  if (to) dto.ingestedBefore = new Date(to.getTime() + 86_400_000).toISOString();
  return Object.keys(dto).length ? dto : undefined;
};

const STORAGE_KEY = 'spectra_filter';

const asStrings = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];

/** Filtre mémorisé pour l'onglet (session), ou filtre vide si absent, illisible ou périmé. */
export const loadFilter = (): FilterState => {
  try {
    const raw = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null') as Partial<FilterState> | null;
    if (!raw || typeof raw !== 'object') return EMPTY_FILTER;
    return {
      categories: asStrings(raw.categories),
      sources: asStrings(raw.sources),
      from: typeof raw.from === 'string' ? raw.from : '',
      to: typeof raw.to === 'string' ? raw.to : '',
    };
  } catch {
    return EMPTY_FILTER;
  }
};

export const saveFilter = (filter: FilterState): void => {
  try {
    if (isFilterActive(filter)) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(filter));
    else sessionStorage.removeItem(STORAGE_KEY);
  } catch { /* stockage indisponible : le filtre vit en mémoire */ }
};

export interface Facet { name: string; count: number }
export interface DocumentFacets { categories: Facet[]; sources: Facet[] }

const tally = (names: string[]): Facet[] => {
  const counts = new Map<string, number>();
  for (const name of names) counts.set(name, (counts.get(name) ?? 0) + 1);
  return [...counts].map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
};

/** Catégories et documents proposés au filtre, du plus fréquent au moins fréquent. */
export const facetsFromDocuments = (docs: Array<{ fileName: string; categories?: string[] }>): DocumentFacets => ({
  categories: tally(docs.flatMap(d => d.categories ?? [])),
  sources: tally(docs.map(d => d.fileName)),
});
