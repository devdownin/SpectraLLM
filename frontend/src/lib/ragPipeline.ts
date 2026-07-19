import type { RagOverridesDto, StreamStageTrace } from '../services/api';

/**
 * Logique pure du pipeline RAG côté Playground, extraite pour être testable indépendamment
 * du composant (calcul de pertinence, construction des surcharges, modules appliqués,
 * formatage de la timeline).
 */

// ── Modules pilotables par requête ──────────────────────────────────────────

export type OverrideKey = keyof RagOverridesDto;

/** Sous-ensemble de RagMeta suffisant pour savoir si un module a agi. */
export interface RagMetaFlags {
  ragStrategy: string;
  hybridSearchApplied?: boolean;
  rerankApplied?: boolean;
  multiQueryApplied?: boolean;
  correctiveApplied?: boolean;
  compressionApplied?: boolean;
  selfRagApplied?: boolean;
}

export interface ModuleDef {
  key: OverrideKey;
  label: string;
  /** Drapeau correspondant dans RagMeta (pour savoir si le module a réellement agi). */
  flag: keyof RagMetaFlags;
  hint: string;
}

export const RAG_MODULES: ModuleDef[] = [
  { key: 'hybrid',       label: 'Hybrid Search',   flag: 'hybridSearchApplied', hint: 'BM25 + vector retrieval (RRF fusion)' },
  { key: 'rerank',       label: 'Cross-Encoder',   flag: 'rerankApplied',       hint: 'Re-rank candidates jointly with the query' },
  { key: 'multiQuery',   label: 'Multi-Query',     flag: 'multiQueryApplied',   hint: 'Generate query variants to broaden retrieval' },
  { key: 'corrective',   label: 'Corrective RAG',  flag: 'correctiveApplied',   hint: 'Grade chunks and drop irrelevant ones' },
  { key: 'compression',  label: 'Compression',     flag: 'compressionApplied',  hint: 'Extract only relevant sentences from chunks' },
  { key: 'selfRag',      label: 'Self-RAG',        flag: 'selfRagApplied',      hint: 'Self-evaluate the answer and refine if weak' },
  { key: 'adaptive',     label: 'Adaptive routing', flag: 'ragStrategy',        hint: 'Classify DIRECT / STANDARD / AGENTIC before retrieval' },
];

/**
 * Modules ayant réellement agi sur une réponse (menu de comparaison A/B) : retirer un module
 * qui n'a pas agi ne changerait rien. Adaptive n'est « appliqué » de façon observable que
 * lorsque la stratégie retenue est AGENTIC.
 */
export const appliedModules = (meta?: RagMetaFlags): ModuleDef[] => {
  if (!meta) return [];
  return RAG_MODULES.filter(mod =>
    mod.key === 'adaptive' ? meta.ragStrategy === 'AGENTIC' : meta[mod.flag] === true);
};

/**
 * Convertit un ensemble de modules désactivés en surcharges RagOverrides (chaque module → OFF).
 * Retourne {@code undefined} si rien n'est désactivé (= défaut serveur, aucun override envoyé).
 * @param extra module supplémentaire à désactiver (comparaison A/B : base de session + module comparé)
 */
export const overridesFromDisabled = (
  disabled: Iterable<OverrideKey>, extra?: OverrideKey,
): RagOverridesDto | undefined => {
  const off = new Set(disabled);
  if (extra) off.add(extra);
  if (off.size === 0) return undefined;
  const ov: RagOverridesDto = {};
  off.forEach(k => { ov[k] = false; });
  return ov;
};

// ── Pertinence des sources ──────────────────────────────────────────────────

/** Forme minimale d'une source pour le calcul de pertinence. */
export interface SourceLike {
  distance: number;
  bm25Score?: number | null;
  rerankScore?: number | null;
}

/**
 * Chunk retrouvé uniquement par BM25 (recherche hybride) : ChromaDB lui affecte la distance
 * sentinelle 1.0 — afficher « 0% de pertinence » serait trompeur.
 */
export const isBm25Only = (src: SourceLike): boolean => (src.bm25Score ?? 0) > 0 && src.distance >= 0.999;

/** Pertinence vectorielle en % (1 − distance cosinus), null si non calculable ou chunk BM25-only. */
export const relevancePct = (src: SourceLike): number | null => {
  if (typeof src.distance !== 'number' || isBm25Only(src)) return null;
  return Math.max(0, Math.min(100, Math.round((1 - src.distance) * 100)));
};

// ── Timeline serveur ────────────────────────────────────────────────────────

/** Formatte une durée en ms lisible (ms sous la seconde, s au-delà). */
export const fmtMs = (ms: number): string =>
  ms >= 1000 ? `${(ms / 1000).toFixed(ms >= 10000 ? 0 : 1)}s` : `${Math.round(ms)}ms`;

/**
 * Formate les compteurs d'une étape de la timeline : itérations pour la boucle agentique,
 * `avant→après chunks (−N)` pour les étapes filtrantes, `N chunks` pour un simple retrieval,
 * {@code null} si l'étape ne porte pas de compteur (routing, génération…).
 */
export const formatStageCounts = (s: StreamStageTrace): string | null => {
  if (s.stage === 'agentic' && typeof s.outCount === 'number') return `${s.outCount} iter`;
  if (typeof s.inCount === 'number' && typeof s.outCount === 'number') {
    return `${s.inCount}→${s.outCount} chunks${s.outCount < s.inCount ? ` (−${s.inCount - s.outCount})` : ''}`;
  }
  if (typeof s.outCount === 'number') return `${s.outCount} chunks`;
  return null;
};
