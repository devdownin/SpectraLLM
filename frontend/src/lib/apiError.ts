/**
 * Message lisible d'une erreur d'API, quelle que soit la forme du corps.
 *
 * Le backend répond majoritairement en `ProblemDetail` (`detail`), mais certaines routes
 * renvoient encore une map ad hoc (`error`). Ne lire que `detail` faisait retomber sur
 * « Request failed with status code 409 » et perdre le seul message actionnable — c'était le
 * cas du refus « un entraînement est déjà en cours » (constat S16).
 */
export function apiErrorMessage(err: unknown, fallback?: string): string | undefined {
  const data = (err as { response?: { data?: unknown } })?.response?.data;
  if (typeof data === 'string' && data.trim()) return data;
  if (data && typeof data === 'object') {
    const body = data as { detail?: unknown; error?: unknown; message?: unknown };
    for (const candidate of [body.detail, body.error, body.message]) {
      if (typeof candidate === 'string' && candidate.trim()) return candidate;
    }
  }
  const message = (err as { message?: unknown })?.message;
  return typeof message === 'string' && message.trim() ? message : fallback;
}

/**
 * Code HTTP d'une erreur d'API, ou `undefined` si l'erreur n'en porte pas
 * (panne réseau, exception locale).
 *
 * <p>Pendant de {@link apiErrorMessage} pour les quelques endroits qui doivent distinguer un
 * cas précis — 409 « déjà en cours », 429 contre-pression — et non seulement afficher un
 * message. Sans lui, ces sites retombaient sur `err: any` juste pour lire `response.status`,
 * ce qui rouvrait la porte aux extractions de message improvisées.
 */
export function apiErrorStatus(err: unknown): number | undefined {
  const status = (err as { response?: { status?: unknown } })?.response?.status;
  return typeof status === 'number' ? status : undefined;
}
