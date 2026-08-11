import { describe, it, expect } from 'vitest';
import { apiErrorMessage, apiErrorStatus } from './apiError';

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

/**
 * Les formes que les sites d'appel réimplémentaient chacun à leur façon.
 *
 * Avant l'adoption généralisée d'`apiErrorMessage`, six formules ad hoc coexistaient dans le
 * frontend. Chacune ne lisait qu'une partie des champs, et retombait donc sur
 * « Request failed with status code N » dès que le backend répondait dans l'autre forme.
 * Ces cas figent ce que chaque formule ratait.
 */
describe('apiErrorMessage — les cas que les formules ad hoc rataient', () => {
  const problemDetail = {
    response: { status: 409, data: { detail: 'Un entraînement est déjà en cours' } },
    message: 'Request failed with status code 409',
  };
  const mapAdHoc = {
    response: { status: 409, data: { error: 'Un entraînement est déjà en cours' } },
    message: 'Request failed with status code 409',
  };

  it('lit un ProblemDetail là où « error seul » retombait sur le message d\'axios', () => {
    // Ce que faisait Documents.tsx : err.response?.data?.error — undefined ici.
    expect(apiErrorMessage(problemDetail)).toBe('Un entraînement est déjà en cours');
  });

  it('lit une map ad hoc là où « detail seul » retombait sur le message d\'axios', () => {
    // Ce que faisaient Comparison.tsx et Ingestion.tsx : data?.detail ?? err.message.
    expect(apiErrorMessage(mapAdHoc)).toBe('Un entraînement est déjà en cours');
  });

  it('lit les deux formes là où « message seul » ratait tout', () => {
    // Ce que faisaient AbComparisonView.tsx et BatchEvaluateDialog.tsx :
    // data?.message ?? err.message — soit « Request failed with status code 409 ».
    expect(apiErrorMessage(problemDetail)).not.toBe('Request failed with status code 409');
    expect(apiErrorMessage(mapAdHoc)).not.toBe('Request failed with status code 409');
  });
});

describe('apiErrorStatus', () => {
  it('rend le code HTTP quand il existe', () => {
    expect(apiErrorStatus({ response: { status: 429 } })).toBe(429);
  });

  it('rend undefined sur une panne réseau ou une exception locale', () => {
    // Sans réponse, il n'y a pas de statut : les sites qui testent « === 429 » doivent
    // basculer sur le cas général plutôt que de comparer à undefined par accident.
    expect(apiErrorStatus({ message: 'Network Error' })).toBeUndefined();
    expect(apiErrorStatus(new Error('boom'))).toBeUndefined();
    expect(apiErrorStatus(undefined)).toBeUndefined();
  });

  it('ignore un statut non numérique', () => {
    expect(apiErrorStatus({ response: { status: '429' } })).toBeUndefined();
  });
});
