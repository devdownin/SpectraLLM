export type SourceType = 'LOCAL_FOLDER' | 'S3' | 'WEB';
export type SyncStatus = 'IDLE' | 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'INTERRUPTED' | 'CANCELLED';
export interface SourceConfig {
  rootPath?: string; url?: string; sitemapUrl?: string; bucket?: string; prefix?: string;
  endpoint?: string; region?: string; accessKeyEnv?: string; secretKeyEnv?: string; sessionTokenEnv?: string;
  extensions: string[]; excludePatterns: string[]; maxDocuments: number; maxDepth: number; maxFileSizeMb: number;
}
export interface SourceRequest {
  name: string; type: SourceType; collection: string; enabled: boolean; intervalMinutes: number; config: SourceConfig;
}
export interface SourceRun {
  id: string; sourceId: string; status: SyncStatus; scanned: number; indexed: number; unchanged: number;
  deleted: number; failed: number; startedAt: string; completedAt: string | null; error: string | null;
}
export interface Source extends SourceRequest {
  id: string; status: SyncStatus; documentCount: number; lastSuccessAt: string | null; nextSyncAt: string | null;
  lastError: string | null; lastRun: SourceRun | null;
}
export interface SourceDocument {
  id: string; externalId: string; title: string; sourceUrl: string | null; revision: string;
  indexedAt: string | null; lastSeenAt: string | null; status: string; error: string | null;
}
export interface SourceCatalogEntry { type: SourceType; name: string; icon: string; available: boolean; capabilities: string[] }
export interface ConnectionResult { success: boolean; message: string; sampleCount: number }
export interface DocumentPage { content: SourceDocument[]; number: number; totalPages: number; totalElements: number }
