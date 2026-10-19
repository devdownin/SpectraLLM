import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { queryApi } from './api';

const sse = (body: string) => new Response(new ReadableStream({
  start(controller) { controller.enqueue(new TextEncoder().encode(body)); controller.close(); },
}), { status: 200 });

const sentBody = (fetchMock: ReturnType<typeof vi.fn>) => JSON.parse(fetchMock.mock.calls[0][1].body as string);

async function drain(filter?: Parameters<typeof queryApi.queryStream>[9], useRag = true) {
  const events: string[] = [];
  for await (const e of queryApi.queryStream('Q ?', useRag, undefined, undefined, undefined, undefined, undefined, undefined, 'col', filter)) {
    events.push(e.type);
  }
  return events;
}

describe('queryStream filter', () => {
  const fetchMock = vi.fn();
  beforeEach(() => { fetchMock.mockReset(); fetchMock.mockImplementation(async () => sse('event:done\ndata:{}\n\n')); vi.stubGlobal('fetch', fetchMock); });
  afterEach(() => vi.unstubAllGlobals());

  it('sends the filter when it restricts something', async () => {
    expect(await drain({ categories: ['juridique'], ingestedAfter: '2026-01-01T00:00:00.000Z' })).toEqual(['done']);
    expect(sentBody(fetchMock)).toMatchObject({ useRag: true, collection: 'col', filter: { categories: ['juridique'], ingestedAfter: '2026-01-01T00:00:00.000Z' } });
  });

  it('omits an empty or undefined filter, so the server searches the whole corpus', async () => {
    await drain({ categories: [], sources: [] });
    expect(sentBody(fetchMock)).not.toHaveProperty('filter');
    fetchMock.mockClear();
    await drain(undefined);
    expect(sentBody(fetchMock)).not.toHaveProperty('filter');
  });

  it('never sends a filter to a question answered without RAG', async () => {
    await drain({ categories: ['juridique'] }, false);
    expect(sentBody(fetchMock)).not.toHaveProperty('filter');
  });
});
