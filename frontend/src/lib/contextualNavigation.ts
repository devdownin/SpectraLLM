import type { GlobalTask } from '../hooks/useGlobalTasks';

export function taskDestination(task: Pick<GlobalTask, 'id' | 'kind' | 'path'>) {
  const rawId = task.id.slice(task.kind.length + 1);
  const params = new URLSearchParams();
  if (task.kind === 'evaluation') params.set('eval', rawId);
  else if (task.kind === 'ab') params.set('ab', rawId);
  else if (task.kind === 'training' || task.kind === 'ablation') params.set('job', rawId);
  else params.set('task', task.id);
  return `${task.path}?${params}`;
}

export interface Origin { path: string; label: string; state?: unknown }
export function validOrigin(value: unknown): Origin | null {
  if (!value || typeof value !== 'object') return null;
  const origin = value as Origin;
  if (typeof origin.path !== 'string' || !/^\/(?:activity|playground|fine-tuning|comparison|optimization|model-hub|ingestion|documents)(?:[?#].*)?$/.test(origin.path) || typeof origin.label !== 'string') return null;
  return origin;
}
