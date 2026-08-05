import { describe, it, expect } from 'vitest';
import { stepStates, PIPELINE_STEPS } from './fineTuningSteps';

describe('stepStates', () => {
  it('allume TOUTES les étapes d\'un job terminé, jalon final compris', () => {
    // Constat S6 : `isActive` excluait explicitement COMPLETED et `isDone` testait `i < current`,
    // si bien que la dernière pastille restait grise — un run réussi ne montrait jamais sa fin.
    expect(stepStates('COMPLETED')).toEqual(Array(PIPELINE_STEPS.length).fill('done'));
  });

  it('marque l\'étape courante active et les précédentes franchies', () => {
    expect(stepStates('TRAINING')).toEqual(['done', 'done', 'active', 'todo', 'todo']);
  });

  it('n\'affirme aucun avancement pour un job échoué', () => {
    // La phase réellement fautive n'est pas conservée par le backend (S5) : tant qu'elle ne
    // l'est pas, aucune étape ne doit être présentée comme franchie.
    const states = stepStates('FAILED');
    expect(states.filter((s) => s === 'done')).toHaveLength(0);
    expect(states.filter((s) => s === 'failed')).toHaveLength(1);
  });

  it('n\'a aucune étape active avant le démarrage', () => {
    expect(stepStates('PENDING')).toEqual(['active', 'todo', 'todo', 'todo', 'todo']);
  });
});
