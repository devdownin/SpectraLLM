import { useState, useEffect, useRef } from 'react';

export type SseStatus = 'connecting' | 'open' | 'closed';

interface SseOptions {
  /** Nombre maximal de tentatives de reconnexion avant d'abandonner (défaut 6). */
  maxRetries?: number;
}

/**
 * Abonnement Server-Sent Events avec reconnexion automatique (backoff exponentiel).
 *
 * Retourne le dernier message parsé, la dernière erreur et l'état de connexion
 * (`connecting` | `open` | `closed`) afin que l'UI puisse signaler une coupure
 * — le flux peut tomber en cours de run long (CPU) sans que l'utilisateur le sache.
 */
export function useSse<T>(url: string, options?: SseOptions) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Event | null>(null);
  const [status, setStatus] = useState<SseStatus>('connecting');
  const retriesRef = useRef(0);

  const maxRetries = options?.maxRetries ?? 6;

  useEffect(() => {
    let es: EventSource | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let closedByUs = false;

    const connect = () => {
      setStatus('connecting');
      es = new EventSource(url);

      es.onopen = () => {
        retriesRef.current = 0;
        setStatus('open');
      };

      es.onmessage = (event) => {
        try {
          setData(JSON.parse(event.data));
        } catch (e) {
          console.error('Error parsing SSE data', e);
        }
      };

      es.onerror = (err) => {
        setError(err);
        es?.close();
        if (closedByUs) return;
        if (retriesRef.current >= maxRetries) {
          setStatus('closed');
          return;
        }
        // Backoff exponentiel plafonné à 15 s.
        const delay = Math.min(1000 * 2 ** retriesRef.current, 15000);
        retriesRef.current += 1;
        setStatus('connecting');
        reconnectTimer = setTimeout(connect, delay);
      };
    };

    connect();

    return () => {
      closedByUs = true;
      if (reconnectTimer) clearTimeout(reconnectTimer);
      es?.close();
    };
  }, [url, maxRetries]);

  return { data, error, status };
}
