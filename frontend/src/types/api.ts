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

export interface QueryResponse {
  answer: string;
  sources: string[];
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
