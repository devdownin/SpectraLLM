import { describe, it, expect } from 'vitest';
import { apiErrorMessage } from './apiError';

/**
 * Extraction du message d'erreur d'une réponse d'API.
 *
 * Le backend répond majoritairement en `ProblemDetail` (`detail`), mais certaines routes
 * renvoient encore une map ad hoc (`error`). Ne lire que `detail` faisait retomber sur
 * « Request failed with status code 409 » et perdre le seul message actionnable (constat S16).
 */
describe('apiErrorMessage', () => {
  it('préfère le detail d\'un ProblemDetail', () => {
    expect(apiErrorMessage({
      response: { data: { detail: 'Entraînement déjà en cours', error: 'ignoré' } },
      message: 'Request failed with status code 409',
    })).toBe('Entraînement déjà en cours');
  });

  it('accepte la forme historique { error }', () => {
    expect(apiErrorMessage({
      response: { data: { error: 'Un entraînement est déjà en cours' } },
      message: 'Request failed with status code 409',
    })).toBe('Un entraînement est déjà en cours');
  });

  it('accepte un corps textuel', () => {
    expect(apiErrorMessage({ response: { data: 'Service indisponible' } }))
      .toBe('Service indisponible');
  });

  it('ignore les champs vides et retombe sur le message de l\'exception', () => {
    expect(apiErrorMessage({
      response: { data: { detail: '   ', error: '' } },
      message: 'Network Error',
    })).toBe('Network Error');
  });

  it('rend le repli fourni quand rien n\'est exploitable', () => {
    expect(apiErrorMessage({}, 'Échec inconnu')).toBe('Échec inconnu');
    expect(apiErrorMessage(undefined)).toBeUndefined();
  });
});
