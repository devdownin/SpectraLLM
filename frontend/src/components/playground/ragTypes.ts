import type { StreamStageTrace, RagOverridesDto } from '../../services/api';

/**
 * Types et constantes partagés du Playground, extraits pour être importés à la fois par la page
 * et par ses panneaux chargés à la demande (`RagComparisonDialog`, `RagTracePanel`) sans créer de
 * dépendance circulaire à l'exécution.
 */

export interface Source {
  preview?: string;
  text?: string;
  sourceFile: string;
  distance: number;
  /** Score du Cross-Encoder (re-ranking), si appliqué. */
  rerankScore?: number | null;
  /** Score BM25 brut (recherche hybride), si appliqué. */
  bm25Score?: number | null;
}

export interface RagMeta {
  conversationalApplied: boolean;
  correctiveApplied: boolean;
  selfRagApplied: boolean;
  ragStrategy: string;
  rerankApplied: boolean;
  hybridSearchApplied: boolean;
  multiQueryApplied: boolean;
  compressionApplied: boolean;
  semanticDedupApplied: boolean;
  longContextApplied: boolean;
  agenticIterations?: number;
  /** Raison d'arrêt de la boucle agentique (ANSWER, MAX_ITERATIONS…). */
  agenticStopReason?: string;
  /** Nombre de chunks injectés dans le contexte final. */
  chunkCount?: number;
  /** Question autonome utilisée pour le retrieval (Conversational RAG). */
  rewrittenQuestion?: string;
  /** Scores de réflexion Self-RAG « ISREL/ISSUP/ISUSE ». */
  selfRagScores?: string;
  /** Chronologie serveur des étapes (durée + compteurs), pour la timeline du Trace. */
  stages?: StreamStageTrace[];
  /** Taille (caractères) du contexte récupéré injecté dans le prompt (budget de tokens estimé). */
  contextChars?: number;
}

export interface MessageMetrics {
  ttftMs: number;   // time to first token
  totalMs: number;  // total generation time
  tokens: number;   // approximate token count
}

/**
 * Paramètres effectifs de la requête ayant produit une réponse. Mémorisés sur le message
 * pour que la comparaison A/B rejoue à partir de SA configuration réelle (« toutes choses
 * égales par ailleurs »), et non des réglages courants de la session qui ont pu changer depuis.
 */
export interface RequestParams {
  temperature: number;
  topP: number;
  topCandidates: number;
  overrides?: RagOverridesDto;
}

export interface Message {
  role: 'user' | 'assistant';
  content: string;
  sources?: Source[];
  ragMeta?: RagMeta;
  status?: 'PENDING' | 'SENT' | 'ERROR' | 'STREAMING';
  metrics?: MessageMetrics;
  /** Paramètres effectifs de la requête (température, top-p, candidats, surcharges de modules). */
  params?: RequestParams;
  feedback?: 'UP' | 'DOWN';
  /** Réponse interrompue par l'utilisateur (Stop) — donc potentiellement incomplète. */
  stopped?: boolean;
  /** Message purement local (accueil, « discussion effacée ») : jamais envoyé dans
   *  l'historique conversationnel au backend — il polluerait la reformulation. */
  local?: boolean;
}

export const STRATEGY_COLORS: Record<string, string> = {
  DIRECT:   'border-secondary/40 text-secondary bg-secondary/5',
  STANDARD: 'border-outline-variant/30 text-outline',
  AGENTIC:  'border-primary/40 text-primary bg-primary/5',
};

/** Libellés lisibles des événements SSE `stage` (étape du pipeline RAG côté backend). */
export const STAGE_LABELS: Record<string, string> = {
  routing:     'Classifying question complexity…',
  rewriting:   'Rephrasing question with conversation history…',
  retrieval:   'Searching the knowledge base…',
  grading:     'Grading retrieved chunks…',
  compression: 'Compressing context…',
  reflection:  'Self-evaluating the answer…',
  refining:    'Refining the answer…',
};
