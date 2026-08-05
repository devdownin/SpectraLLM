import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSse } from './useSse';

/**
 * Livraison des évènements SSE (constat S10).
 *
 * `data` n'expose que le **dernier** message reçu : deux évènements traités avant qu'un rendu
 * n'ait lieu n'en laissent qu'un seul observable. Sous rafale — une barre de progression en
 * produit des centaines par seconde — des lignes disparaissaient donc du flux de télémétrie,
 * sans compteur ni marqueur de trou, pendant que l'en-tête affirmait « N events ».
 */

let sse: FakeEventSource | null = null;
const registerSse = (instance: FakeEventSource) => { sse = instance; };

class FakeEventSource {
  onopen: ((e: unknown) => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: ((e: unknown) => void) | null = null;
  close = vi.fn();
  constructor() { registerSse(this); }
}
vi.stubGlobal('EventSource', FakeEventSource);

const emit = (payload: unknown) => sse?.onmessage?.({ data: JSON.stringify(payload) });

beforeEach(() => {
  sse = null;
  vi.clearAllMocks();
});

describe('useSse', () => {
  it('livre CHAQUE évènement au rappel, même sans rendu intermédiaire', () => {
    const received: number[] = [];
    renderHook(() => useSse<{ n: number }>('/api/sse/x', {
      onMessage: (m) => received.push(m.n),
    }));

    // Trois messages dans le même tick : c'est exactement le cas que `data` ne peut pas rendre.
    act(() => {
      emit({ n: 1 });
      emit({ n: 2 });
      emit({ n: 3 });
    });

    expect(received).toEqual([1, 2, 3]);
  });

  it('expose tout de même le dernier message pour les consommateurs simples', () => {
    const { result } = renderHook(() => useSse<{ n: number }>('/api/sse/x'));

    act(() => { emit({ n: 7 }); });

    expect(result.current.data).toEqual({ n: 7 });
  });

  it('ne rouvre pas la connexion quand le rappel change d\'identité', () => {
    // Le rappel est recréé à chaque rendu du consommateur ; le mettre en dépendance de l'effet
    // fermerait et rouvrirait le flux en boucle.
    const { rerender } = renderHook(
      ({ cb }) => useSse<{ n: number }>('/api/sse/x', { onMessage: cb }),
      { initialProps: { cb: () => {} } },
    );
    const first = sse;

    rerender({ cb: () => {} });

    expect(sse).toBe(first);
    expect(first?.close).not.toHaveBeenCalled();
  });

  it('signale une erreur de parsing sans interrompre le flux', () => {
    const received: unknown[] = [];
    renderHook(() => useSse('/api/sse/x', { onMessage: (m) => received.push(m) }));

    act(() => {
      sse?.onmessage?.({ data: 'pas du JSON' });
      emit({ ok: true });
    });

    expect(received).toEqual([{ ok: true }]);
  });
});
