import { ingestApi } from '../services/api';

interface DocumentIdentity { fileName: string; sha256: string; collectionName?: string }
/** Filename is not an identity: inspect all search pages and never pick a fuzzy/duplicate match. */
export async function resolveSourceDocument(fileName: string, collection?: string): Promise<string> {
  const identities = new Set<string>();
  for (let page = 0; page < 100; page++) {
    const { data } = await ingestApi.getHistory({ q: fileName, size: 100, page });
    const items: DocumentIdentity[] = Array.isArray(data) ? data : data?.content ?? [];
    for (const item of items) if (item.fileName === fileName && item.sha256 && (!collection || item.collectionName === collection)) identities.add(item.sha256);
    if (identities.size > 1) throw new Error('ambiguous');
    const done = Array.isArray(data) || data?.last === true || (typeof data?.totalPages === 'number' ? page + 1 >= data.totalPages : items.length < 100);
    if (done) {
      if (!identities.size) throw new Error('missing');
      return [...identities][0];
    }
  }
  throw new Error('ambiguous');
}
