import type { BadgeTone } from '../components/ui/Badge';

export type StatusDomain = 'task' | 'document' | 'model';
const running = new Set(['RUNNING', 'PROCESSING', 'TRAINING', 'EXPORTING_DATASET', 'IMPORTING_MODEL', 'DOWNLOADING', 'REGISTERING', 'UPLOADING']);
const taskTones: Record<string, BadgeTone> = { pending: 'neutral', running: 'secondary', completed: 'success', partial: 'warning', failed: 'error', cancelled: 'neutral' };
const documentTones: Record<string, BadgeTone> = { INGESTED: 'neutral', QUALIFIED: 'secondary', TRAINED: 'success', ARCHIVED: 'neutral' };
const modelTones: Record<string, BadgeTone> = { configured: 'neutral', loading: 'warning', loaded: 'success', unavailable: 'error', unverified: 'neutral' };

/** Only known states receive a semantic label; unknown backend states stay neutral. */
export function describeStatus(status: string | null | undefined, domain: StatusDomain = 'task') {
  const raw = status ?? "";
  let key: string;
  let tones = taskTones;
  if (domain === 'task') {
    key = raw.toLowerCase();
    if (running.has(raw.toUpperCase())) key = 'running';
  } else if (domain === 'document') {
    key = raw.toUpperCase(); tones = documentTones;
  } else { key = raw.toLowerCase(); tones = modelTones; }
  return { tone: tones[key] ?? 'neutral', labelKey: tones[key] ? `ui.status.${domain}.${key}` : null } as const;
}

export interface ModelService {
  available?: boolean;
  details?: { activeModelLoaded?: boolean; activeModel?: string };
}
export function modelPhase(service?: ModelService): string {
  if (!service || service.available === undefined) return 'unverified';
  if (!service.available) return 'unavailable';
  if (service.details?.activeModelLoaded === false) return 'loading';
  if (service.details?.activeModelLoaded === true && service.details.activeModel) return 'loaded';
  return 'unverified';
}
