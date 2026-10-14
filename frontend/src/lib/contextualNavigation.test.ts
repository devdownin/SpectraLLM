import { expect, it } from 'vitest';
import { taskDestination, validOrigin } from './contextualNavigation';
import type { GlobalTaskKind } from '../hooks/useGlobalTasks';
it.each([
  ['evaluation', '/comparison', 'eval'], ['ab', '/comparison', 'ab'], ['training', '/fine-tuning', 'job'], ['ablation', '/optimization', 'job'],
  ['ingestion', '/ingestion', 'task'], ['dataset', '/ingestion', 'task'], ['dpo', '/ingestion', 'task'], ['install', '/model-hub', 'task'], ['benchmark', '/model-hub', 'task'],
])('links %s to the exact identifier, including reserved characters', (kind, path, key) => {
  const url = new URL(taskDestination({ id: `${kind}:a:b/&?`, kind: kind as GlobalTaskKind, path }), 'http://local');
  expect(url.pathname).toBe(path);
  expect(url.searchParams.get(key)).toBe(key === 'task' ? `${kind}:a:b/&?` : 'a:b/&?');
});
it.each(['https://evil.test', '//evil.test', '/activity/../other', '/unknown', null, {}])('rejects an unsafe or unknown origin %s', value => {
  expect(validOrigin(typeof value === 'string' ? { path: value, label: 'Origin' } : value)).toBeNull();
});
it('preserves an internal origin with its filters', () => {
  const origin = { path: '/activity?q=run', label: 'Activity', state: { activityFilters: { status: 'failed' } } };
  expect(validOrigin(origin)).toEqual(origin);
});
