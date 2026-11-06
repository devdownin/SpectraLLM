import type { BadgeTone } from '../ui';
import type { Source, SourceRequest, SourceType, SyncStatus } from '../../types/sources';
export const SOURCE_TYPES: SourceType[] = ['LOCAL_FOLDER', 'S3', 'WEB'];
export const sourceIcons: Record<SourceType, string> = { LOCAL_FOLDER: 'folder_open', S3: 'cloud', WEB: 'language' };
export const statusTones: Record<SyncStatus | 'PAUSED', BadgeTone> = {
  IDLE: 'neutral', RUNNING: 'primary', SUCCESS: 'success', PARTIAL: 'warning', FAILED: 'error', INTERRUPTED: 'warning', CANCELLED: 'neutral', PAUSED: 'neutral',
};
export const sourceStatus = (source: Source): SyncStatus | 'PAUSED' => source.status === 'RUNNING' ? 'RUNNING' : !source.enabled ? 'PAUSED' : source.status;
export const sourceLocation = (source: SourceRequest) => source.type === 'LOCAL_FOLDER' ? source.config.rootPath : source.type === 'WEB' ? source.config.url : `s3://${source.config.bucket ?? ''}/${source.config.prefix ?? ''}`;
export const sourceRequest = (source: Source): SourceRequest => ({ name: source.name, type: source.type, collection: source.collection, enabled: source.enabled, intervalMinutes: source.intervalMinutes, config: source.config });
export const newSource = (type: SourceType = 'LOCAL_FOLDER'): SourceRequest => ({
  name: '', type, collection: 'spectra_documents', enabled: true, intervalMinutes: 60,
  config: { extensions: [], excludePatterns: [], maxDocuments: 1000, maxDepth: 2, maxFileSizeMb: 25, region: 'us-east-1' },
});
export const displayDate = (value: string | null | undefined, locale: string) => value && !Number.isNaN(Date.parse(value)) ? new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
export const safeWebLink = (value: string | null | undefined) => {
  try { const url = new URL(value ?? ''); return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined; } catch { return undefined; }
};
