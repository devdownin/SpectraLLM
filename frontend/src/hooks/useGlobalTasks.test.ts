import { describe, it, expect } from 'vitest';
import {
  toStatus,
  ratio,
  isActiveTask,
  normalizeIngestTasks,
  normalizeDatasetTasks,
  normalizeDpoTasks,
  normalizeTrainingJobs,
  normalizeEvaluations,
  normalizeAbComparisons,
  normalizeInstallations,
  normalizeBenchmarkJobs,
} from './useGlobalTasks';

describe('toStatus', () => {
  it('maps the heterogeneous backend statuses onto the 4 normalized states', () => {
    expect(toStatus('COMPLETED')).toBe('completed');
    expect(toStatus('FAILED')).toBe('failed');
    expect(toStatus('CANCELLED')).toBe('failed');
    expect(toStatus('PENDING')).toBe('pending');
    // Running states across all task families
    for (const s of ['PROCESSING', 'RUNNING', 'TRAINING', 'EXPORTING_DATASET',
                     'IMPORTING_MODEL', 'DOWNLOADING', 'REGISTERING', 'UPLOADING']) {
      expect(toStatus(s)).toBe('running');
    }
  });

  it('treats unknown or missing statuses as pending (never crashes the header)', () => {
    expect(toStatus(undefined)).toBe('pending');
    expect(toStatus(null)).toBe('pending');
    expect(toStatus('SOME_FUTURE_STATE')).toBe('pending');
  });
});

describe('ratio', () => {
  it('computes a clamped 0..1 ratio', () => {
    expect(ratio(5, 10)).toBe(0.5);
    expect(ratio(15, 10)).toBe(1);
    expect(ratio(-2, 10)).toBe(0);
  });

  it('returns null when the total is unknown or zero (indeterminate progress)', () => {
    expect(ratio(5, 0)).toBeNull();
    expect(ratio(5, undefined)).toBeNull();
    expect(ratio(undefined, 10)).toBeNull();
  });
});

describe('normalizers', () => {
  it('tolerate a non-array payload (API error body, null…)', () => {
    for (const normalize of [normalizeIngestTasks, normalizeDatasetTasks, normalizeDpoTasks,
                             normalizeTrainingJobs, normalizeEvaluations, normalizeAbComparisons,
                             normalizeInstallations, normalizeBenchmarkJobs]) {
      expect(normalize(null)).toEqual([]);
      expect(normalize({ error: 'boom' })).toEqual([]);
    }
  });

  it('prefix ids per kind so tasks from different sources never collide', () => {
    const sameId = [{ taskId: 'abc', status: 'PENDING' }];
    const ingest = normalizeIngestTasks(sameId)[0];
    const dataset = normalizeDatasetTasks(sameId)[0];
    expect(ingest.id).toBe('ingestion:abc');
    expect(dataset.id).toBe('dataset:abc');
  });

  it('normalizes an ingestion task (file names, chunk count, no determinate progress)', () => {
    const [task] = normalizeIngestTasks([{
      taskId: 't1', status: 'PROCESSING', files: ['a.pdf', 'b.docx'], chunksCreated: 12, error: null,
    }]);
    expect(task).toMatchObject({
      kind: 'ingestion', label: 'a.pdf, b.docx', detail: '12 chunks',
      status: 'running', progress: null, path: '/ingestion',
    });
  });

  it('normalizes a dataset generation task with chunk-based progress', () => {
    const [task] = normalizeDatasetTasks([{
      taskId: 'gen-12345678', status: 'PROCESSING',
      chunksProcessed: 20, totalChunks: 40, pairsGenerated: 55, error: null,
    }]);
    expect(task.progress).toBe(0.5);
    expect(task.detail).toBe('20/40 chunks · 55 pairs');
    expect(task.label).toBe('GEN-1234');
  });

  it('normalizes a training job with epoch-based progress only while running', () => {
    const running = normalizeTrainingJobs([{
      jobId: 'j1', status: 'TRAINING', modelName: 'spectra-domain',
      currentEpoch: 2, totalEpochs: 4, createdAt: '2026-07-13T10:00:00Z',
    }])[0];
    expect(running).toMatchObject({
      label: 'spectra-domain', detail: 'epoch 2/4', status: 'running',
      progress: 0.5, path: '/fine-tuning', timestamp: '2026-07-13T10:00:00Z',
    });

    const done = normalizeTrainingJobs([{
      jobId: 'j2', status: 'COMPLETED', modelName: 'm', currentEpoch: 4, totalEpochs: 4,
      createdAt: '2026-07-13T10:00:00Z', completedAt: '2026-07-13T11:00:00Z',
    }])[0];
    expect(done.progress).toBeNull();
    expect(done.timestamp).toBe('2026-07-13T11:00:00Z'); // fin > création
  });

  it('normalizes a model installation (percent → 0..1, clamped)', () => {
    const [task] = normalizeInstallations([{
      jobId: 'i1', status: 'DOWNLOADING', modelName: 'phi3', progress: 45, createdAt: '2026-07-13T09:00:00Z',
    }]);
    expect(task).toMatchObject({
      kind: 'install', label: 'phi3', detail: '45%', status: 'running',
      progress: 0.45, path: '/model-hub',
    });
    expect(normalizeInstallations([{ jobId: 'i2', status: 'DOWNLOADING', progress: 250 }])[0].progress).toBe(1);
  });

  it('normalizes evaluations and A/B comparisons toward /comparison', () => {
    const [ev] = normalizeEvaluations([{
      evalId: 'e1', status: 'RUNNING', modelName: 'm1', processed: 3, testSetSize: 12,
    }]);
    expect(ev).toMatchObject({ label: 'm1', detail: '3/12', progress: 0.25, path: '/comparison' });

    const [ab] = normalizeAbComparisons([{
      abId: 'ab1', status: 'CANCELLED', modelA: 'm1', modelB: 'm2', processed: 5, testSetSize: 10,
    }]);
    expect(ab).toMatchObject({ label: 'm1 vs m2', status: 'failed' });
  });

  it('normalizes DPO and quality benchmark jobs', () => {
    const [dpo] = normalizeDpoTasks([{ taskId: 'd1', status: 'PROCESSING', processed: 1, total: 4 }]);
    expect(dpo).toMatchObject({ kind: 'dpo', detail: '1/4', progress: 0.25, status: 'running' });

    const [bench] = normalizeBenchmarkJobs([{
      jobId: 'q1', status: 'RUNNING', baseline: 'old', candidate: 'new',
    }]);
    expect(bench).toMatchObject({ kind: 'benchmark', label: 'new vs old', status: 'running', path: '/model-hub' });
  });

  it('survives entries with missing fields (defensive against partial payloads)', () => {
    const [task] = normalizeTrainingJobs([{}]);
    expect(task.label).toBe('—');
    expect(task.status).toBe('pending');
    expect(task.progress).toBeNull();
  });
});

describe('isActiveTask', () => {
  it('counts pending and running tasks as active, finished ones as not', () => {
    const base = normalizeIngestTasks([{ taskId: 'x', status: 'PENDING' }])[0];
    expect(isActiveTask(base)).toBe(true);
    expect(isActiveTask({ ...base, status: 'running' })).toBe(true);
    expect(isActiveTask({ ...base, status: 'completed' })).toBe(false);
    expect(isActiveTask({ ...base, status: 'failed' })).toBe(false);
  });
});
