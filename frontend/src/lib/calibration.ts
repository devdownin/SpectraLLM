import type { BenchmarkEntry, CalibrationRequestBody, RagOverrides } from '../types/api';

/**
 * Calibration des réglages RAG sur le corpus — logique pure, sans React : grille de réglages,
 * validation du benchmark relu, et traduction du bras recommandé en variables d'environnement.
 */

/** Combinaisons au plus : au-delà, une passe sur CPU dure des heures (même plafond que le serveur). */
export const MAX_ARMS = 16;

export const RERANK_SCORES = [0, 0.1, 0.2, 0.3, 0.5] as const;
export const NEIGHBOR_WINDOWS = [0, 1, 2] as const;
export const DEDUP_STRATEGIES = ['none', 'jaccard', 'mmr'] as const;

export interface GridState {
  rerankMinScores: number[];
  neighborWindows: number[];
  dedupStrategies: string[];
}

/** Grille par défaut : trois seuils autour de la zone usuelle, voisins et déduplication à la configuration. */
export const DEFAULT_GRID: GridState = { rerankMinScores: [0, 0.1, 0.2], neighborWindows: [], dedupStrategies: [] };

/** Une liste vide laisse le réglage à sa configuration : elle compte pour une seule valeur. */
export const armCount = (grid: GridState): number =>
  Math.max(1, grid.rerankMinScores.length) * Math.max(1, grid.neighborWindows.length) * Math.max(1, grid.dedupStrategies.length);

const sorted = (values: number[]): number[] => [...values].sort((a, b) => a - b);

export const toCalibrationBody = (grid: GridState, maxContextChunks: number, runs: number): CalibrationRequestBody => ({
  rerankMinScores: sorted(grid.rerankMinScores),
  neighborWindows: sorted(grid.neighborWindows),
  dedupStrategies: DEDUP_STRATEGIES.filter(s => grid.dedupStrategies.includes(s)),
  maxContextChunks,
  runs,
});

// ── Benchmark relu ────────────────────────────────────────────────────────────

/** Part de questions hors corpus conseillée : de quoi mesurer l'abstention sans noyer le reste. */
export const UNANSWERABLE_TARGET = { min: 0.1, max: 0.2 } as const;

export const unanswerableShare = (entries: BenchmarkEntry[]): number =>
  entries.length ? entries.filter(e => !e.answerable).length / entries.length : 0;

/** Indices (0-based) des entrées que le serveur refuserait : question vide, ou réponse attendue manquante. */
export const invalidEntries = (entries: BenchmarkEntry[]): number[] =>
  entries.flatMap((e, i) => (!e.question.trim() || (e.answerable && !(e.reference ?? '').trim())) ? [i] : []);

export const outOfCorpusEntry = (): BenchmarkEntry =>
  ({ question: '', reference: null, category: 'general', answerable: false, expectedSources: [] });

/** Brouillon du serveur → entrées éditables. */
export const draftToEntries = (items: Array<Omit<BenchmarkEntry, 'reference'> & { reference: string | null; draft?: boolean }>): BenchmarkEntry[] =>
  items.map(({ question, reference, category, answerable, expectedSources }) =>
    ({ question, reference, category, answerable, expectedSources: expectedSources ?? [] }));

// ── Du bras recommandé au .env ────────────────────────────────────────────────

/**
 * Variables d'environnement qui reproduisent les surcharges d'un bras. Un réglage laissé à la
 * configuration (`null`) n'est pas écrit : le bras ne l'a pas mesuré.
 *
 * Le seuil de la déduplication n'est pas repris : il dépend de la stratégie configurée avant
 * la mesure. Le commentaire le rappelle.
 */
export const envSnippet = (
  overrides: Pick<RagOverrides, 'rerankMinScore' | 'neighborWindow' | 'dedupStrategy'> | null | undefined,
  texts: { header: string; dedupThreshold: string },
): string => {
  const lines: string[] = [];
  if (overrides?.rerankMinScore != null) lines.push(`SPECTRA_RERANKER_MIN_SCORE=${overrides.rerankMinScore}`);
  if (overrides?.neighborWindow != null) lines.push(`SPECTRA_RAG_NEIGHBOR_WINDOW=${overrides.neighborWindow}`);
  const dedup = overrides?.dedupStrategy;
  if (dedup === 'none') {
    lines.push('SPECTRA_SEMANTIC_DEDUP_ENABLED=false');
  } else if (dedup) {
    lines.push('SPECTRA_SEMANTIC_DEDUP_ENABLED=true', `SPECTRA_SEMANTIC_DEDUP_STRATEGY=${dedup}`);
    lines.push(`# ${texts.dedupThreshold}`);
  }
  return lines.length ? [`# ${texts.header}`, ...lines].join('\n') : '';
};
