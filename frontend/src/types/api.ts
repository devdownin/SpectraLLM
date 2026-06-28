export interface ServiceStatus {
  name: string;
  url: string;
  available: boolean;
  version: string | null;
}

export interface SystemStatusResponse {
  application: string;
  version: string;
  timestamp: string;
  services: ServiceStatus[];
  gpuUsage?: number;
}

export interface IngestionTask {
  taskId: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
  chunksCreated: number;
  error?: string;
}

export interface DatasetStats {
  totalPairs: number;
  chunksInStore: number;
  byCategory: Record<string, number>;
  avgConfidence: number;
}

export type DocumentLifecycle = 'INGESTED' | 'QUALIFIED' | 'TRAINED' | 'ARCHIVED';

export interface IngestedFile {
  sha256: string;
  fileName: string;
  format: string;
  ingestedAt: string;
  chunksCreated: number;
  lifecycle: DocumentLifecycle;
  version: number;
  tags: string[];
  qualityScore: number | null;
  collectionName: string | null;
}

export interface AuditEntry {
  action: string;
  actor: string;
  timestamp: string;
  details: string;
}

export interface DocumentModelLink {
  model: string;
  type: 'TRAINED_ON' | 'EVALUATED_ON';
  linkedAt: string;
}

export interface IngestedFileSheet extends IngestedFile {
  modelLinks: DocumentModelLink[];
  auditTrail: AuditEntry[];
}

export interface FineTuningJob {
  jobId: string;
  modelName: string;
  baseModel: string;
  status: 'PENDING' | 'TRAINING' | 'COMPLETED' | 'FAILED';
  currentEpoch: number;
  totalEpochs: number;
  loss: number;
  accuracy: number;
  startTime: string;
}

export interface ConversationMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface QuerySource {
  text: string;
  sourceFile: string;
  distance: number;
  rerankScore: number | null;
  bm25Score: number | null;
}

export interface QueryResponse {
  answer: string;
  sources: QuerySource[];
  durationMs: number;
  rerankApplied: boolean;
  hybridSearchApplied: boolean;
  agenticApplied: boolean;
  agenticIterations: number;
  agenticStopReason: string | null;
  conversationalApplied: boolean;
  correctiveApplied: boolean;
  selfRagApplied: boolean;
  ragStrategy: 'DIRECT' | 'STANDARD' | 'AGENTIC';
  multiQueryApplied: boolean;
  compressionApplied: boolean;
  semanticDedupApplied: boolean;
  longContextApplied: boolean;
}

export interface TrainingLog {
  message: string;
  level: 'INFO' | 'WARN' | 'ERROR';
  timestamp: string;
}

export interface EvaluationScore {
  question: string;
  referenceAnswer: string;
  modelAnswer: string;
  score: number;
  justification: string;
  category: string;
  source: string;
}

export interface EvaluationReport {
  evalId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  modelName: string;
  jobId: string | null;
  testSetSize: number;
  processed: number;
  averageScore: number;
  scoresByCategory: Record<string, number>;
  scores: EvaluationScore[];
  error: string | null;
  startedAt: string;
  completedAt: string | null;
}

// ── Ablation / optimisation des réponses ──────────────────────────────────────

/** Surcharges tri-état des modules d'optimisation RAG (null = défaut de déploiement). */
export interface RagOverrides {
  adaptive?: boolean | null;
  conversational?: boolean | null;
  multiQuery?: boolean | null;
  hybrid?: boolean | null;
  rerank?: boolean | null;
  corrective?: boolean | null;
  compression?: boolean | null;
  selfRag?: boolean | null;
}

export interface AblationArmConfig {
  label: string;
  model?: string | null;
  useRag?: boolean;
  overrides?: RagOverrides | null;
}

export interface AblationRequestBody {
  arms: AblationArmConfig[];
  maxContextChunks?: number;
}

export interface RetrievalMetrics {
  evaluatedQuestions: number;
  k: number;
  hitRate: number;
  mrr: number;
  recallAtK: number;
}

export interface AblationQualityReport {
  model: string;
  total: number;
  answerableCount: number;
  unanswerableCount: number;
  avgScore: number;
  hallucinationRate: number;
  refusalAccuracy: number;
  scoresByCategory: Record<string, number>;
  startedAt: string;
  completedAt: string;
}

export interface AblationArmReport {
  label: string;
  model: string;
  useRag: boolean;
  overrides: RagOverrides | null;
  quality: AblationQualityReport;
  retrieval: RetrievalMetrics;
  avgLatencyMs: number;
  p50LatencyMs: number;
  appliedCounts: Record<string, number>;
}

export interface AblationReport {
  arms: AblationArmReport[];
  benchmarkSize: number;
  startedAt: string;
  completedAt: string;
}

export type CommentType = 'HUMAN' | 'AI_GENERATED';
export type CommentRating = 'NONE' | 'APPROVED' | 'REJECTED';

export interface ArticleComment {
  id: number;
  sha256: string;
  content: string;
  author: string;
  type: CommentType;
  rating: CommentRating;
  focus: string;
  createdAt: string;
  updatedAt: string;
}
