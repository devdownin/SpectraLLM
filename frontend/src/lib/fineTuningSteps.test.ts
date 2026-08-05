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

  it('situe l\'échec sur la phase où il s\'est produit', () => {
    // Constat S5 : l'index de l'échec était CONSTANT (l'avant-dernière étape), si bien qu'un
    // dataset vide au filtrage s'affichait comme un échec d'import — l'inverse de ce qui
    // s'était passé. Ici l'export échoue : l'attente est mise en échec à l'étape 2, la file
    // d'attente qui la précède reste franchie, et rien après n'a eu lieu.
    expect(stepStates('FAILED', 'EXPORTING_DATASET'))
      .toEqual(['done', 'failed', 'todo', 'todo', 'todo']);
    expect(stepStates('FAILED', 'IMPORTING_MODEL'))
      .toEqual(['done', 'done', 'done', 'failed', 'todo']);
  });

  it('n\'affirme aucun avancement quand la phase de l\'échec est inconnue', () => {
    // Jobs antérieurs à l'introduction de failedPhase : leur phase est perdue. Plutôt que de
    // supposer, on ne marque aucune étape franchie.
    for (const phase of [undefined, null, 'FAILED' as const]) {
      const states = stepStates('FAILED', phase);
      expect(states.filter((s) => s === 'done')).toHaveLength(0);
      expect(states.filter((s) => s === 'failed')).toHaveLength(1);
    }
  });

  it('rend un arrêt volontaire distinct d\'un échec', () => {
    // Constat S7 : l'annulation écrivait FAILED, si bien qu'un arrêt décidé par l'utilisateur
    // s'affichait comme un incident — badge rouge et toast d'erreur compris.
    const cancelled = stepStates('CANCELLED', 'TRAINING');
    expect(cancelled).toEqual(['done', 'done', 'stopped', 'todo', 'todo']);
    expect(cancelled).not.toContain('failed');

    expect(stepStates('FAILED', 'TRAINING')).toEqual(['done', 'done', 'failed', 'todo', 'todo']);
  });

  it('n\'a aucune étape active avant le démarrage', () => {
    expect(stepStates('PENDING')).toEqual(['active', 'todo', 'todo', 'todo', 'todo']);
  });
});
