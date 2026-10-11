import { beforeEach, expect, it, vi } from 'vitest';
import { resolveSourceDocument } from './sourceDocument';
import { ingestApi } from '../services/api';
vi.mock('../services/api', () => ({ ingestApi: { getHistory: vi.fn() } }));
const history = vi.mocked(ingestApi.getHistory);
beforeEach(() => history.mockReset());
it('finds the exact filename and collection on a later page rather than a fuzzy first result', async () => {
  history.mockResolvedValueOnce({ data: { content: [{ fileName: 'report.pdf.old', sha256: 'wrong', collectionName: 'domain' }], totalPages: 2 } } as never)
    .mockResolvedValueOnce({ data: { content: [{ fileName: 'report.pdf', sha256: 'exact', collectionName: 'domain' }, { fileName: 'report.pdf', sha256: 'other', collectionName: 'elsewhere' }], totalPages: 2 } } as never);
  await expect(resolveSourceDocument('report.pdf', 'domain')).resolves.toBe('exact');
  expect(history).toHaveBeenLastCalledWith({ q: 'report.pdf', size: 100, page: 1 });
});
it('rejects a duplicate filename instead of silently opening an unrelated document', async () => {
  history.mockResolvedValue({ data: [{ fileName: 'same.pdf', sha256: 'one' }, { fileName: 'same.pdf', sha256: 'two' }] } as never);
  await expect(resolveSourceDocument('same.pdf')).rejects.toThrow('ambiguous');
});
it('accepts duplicate entries that have the same identity', async () => {
  history.mockResolvedValue({ data: [{ fileName: 'same.pdf', sha256: 'one' }, { fileName: 'same.pdf', sha256: 'one' }] } as never);
  await expect(resolveSourceDocument('same.pdf')).resolves.toBe('one');
});
it('reports missing exact matches and propagates transport failures', async () => {
  history.mockResolvedValueOnce({ data: [{ fileName: 'near.pdf', sha256: 'near' }] } as never);
  await expect(resolveSourceDocument('wanted.pdf')).rejects.toThrow('missing');
  history.mockRejectedValueOnce(new Error('offline'));
  await expect(resolveSourceDocument('wanted.pdf')).rejects.toThrow('offline');
});
