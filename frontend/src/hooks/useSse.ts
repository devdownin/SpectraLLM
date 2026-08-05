import { useState, useEffect, useRef } from 'react';

export type SseStatus = 'connecting' | 'open' | 'closed';

interface SseOptions<T> {
  /** Nombre maximal de tentatives de reconnexion avant d'abandonner (défaut 6). */
  maxRetries?: number;
  /**
   * Appelé pour **chaque** évènement reçu, dans l'ordre d'arrivée.
   *
   * <p>À préférer à `data` dès qu'aucun message ne doit être perdu : `data` n'expose que le
   * dernier reçu, et deux évènements traités avant un rendu n'en laissent qu'un seul
   * observable. Sous rafale — une barre de progression en produit des centaines par seconde —
   * des lignes disparaissaient donc sans que rien ne le signale.
   *
   * <p>La référence n'a pas besoin d'être stable : elle est lue via une ref, la connexion n'est
   * jamais rouverte à cause d'elle.
   */
  onMessage?: (message: T) => void;
}

/**
 * Abonnement Server-Sent Events avec reconnexion automatique (backoff exponentiel).
 *
 * Retourne le dernier message parsé, la dernière erreur et l'état de connexion
 * (`connecting` | `open` | `closed`) afin que l'UI puisse signaler une coupure
 * — le flux peut tomber en cours de run long (CPU) sans que l'utilisateur le sache.
 */
export function useSse<T>(url: string, options?: SseOptions<T>) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Event | null>(null);
  const [status, setStatus] = useState<SseStatus>('connecting');
  const retriesRef = useRef(0);

  // Miroir du rappel : il change à chaque rendu du consommateur, et le mettre en dépendance de
  // l'effet rouvrirait la connexion SSE à chaque fois.
  const onMessageRef = useRef(options?.onMessage);
  onMessageRef.current = options?.onMessage;

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
          const parsed = JSON.parse(event.data) as T;
          // Le rappel d'abord : il voit chaque évènement, là où `data` ne conserve que le dernier.
          onMessageRef.current?.(parsed);
          setData(parsed);
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
