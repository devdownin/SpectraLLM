import { describe, it, expect } from 'vitest';
import {
  parseProgressLine, mergeLossPoint, epochInProgress, trainingProgressPercent,
} from './trainingProgress';
import type { LossPoint } from './trainingProgress';

/**
 * Règles de lecture de la progression — les trois défauts de suivi qu'elles corrigent sont
 * décrits dans docs/process/audit-suivi-finetuning-ui.fr.md (S1, S2, S3).
 */

describe('parseProgressLine', () => {
  it('conserve la fraction d\'époque — sans quoi toute la première époque vaut 0', () => {
    // Tronquée à l'entier, cette ligne donnait epoch=0, valeur que l'UI testait pour sa
    // véracité : ni barre, ni compteur, ni point de courbe pendant le premier tiers d'un run.
    expect(parseProgressLine('  epoch=0.97  loss=1.2345')).toEqual({ epoch: 0.97, loss: 1.2345 });
  });

  it('distingue eval_loss de la loss d\'entraînement', () => {
    // Sans le garde (?<!eval_), « eval_loss=… » satisfait aussi « loss=… » et la courbe de
    // validation était tracée comme celle d'entraînement — masquant l'écart qu'elle mesure.
    expect(parseProgressLine('  epoch=1.00  eval_loss=0.9500'))
      .toEqual({ epoch: 1, evalLoss: 0.95 });
  });

  it('ignore une ligne sans loss', () => {
    expect(parseProgressLine('Début de l\'entraînement (3 époque(s), LoRA rank=64)...')).toBeNull();
  });

  it('rend une époque nulle quand la ligne n\'en porte pas', () => {
    expect(parseProgressLine('train_loss: 0.51')).toEqual({ epoch: null, loss: 0.51 });
  });
});

describe('mergeLossPoint', () => {
  it('fusionne l\'eval_loss dans le point de la même époque au lieu de l\'écraser', () => {
    // C'est LE correctif S2 : le chemin de sondage remplaçait le point et supprimait donc
    // l'eval_loss déposée par le flux SSE, faisant disparaître la courbe de validation.
    const series: LossPoint[] = [{ epoch: 1, evalLoss: 0.95 }];

    expect(mergeLossPoint(series, { epoch: 1, loss: 0.8 }))
      .toEqual([{ epoch: 1, evalLoss: 0.95, loss: 0.8 }]);
  });

  it('accumule un point par étape, pas un par époque', () => {
    const series = [
      { epoch: 0.33, loss: 1.9 },
      { epoch: 0.67, loss: 1.6 },
    ].reduce<LossPoint[]>((acc, p) => mergeLossPoint(acc, p), []);

    expect(mergeLossPoint(series, { epoch: 1, loss: 1.2 })).toHaveLength(3);
  });

  it('ignore un point antérieur au dernier connu (arrivée hors séquence)', () => {
    const series: LossPoint[] = [{ epoch: 2, loss: 0.5 }];
    expect(mergeLossPoint(series, { epoch: 1, loss: 9 })).toEqual(series);
  });
});

describe('epochInProgress', () => {
  it('affiche la première époque dès la première fraction consommée', () => {
    expect(epochInProgress(0.01, 3)).toBe(1);   // « epoch 0/3 » se lisait comme un blocage
    expect(epochInProgress(1.5, 3)).toBe(2);
  });

  it('ne dépasse jamais le total configuré', () => {
    expect(epochInProgress(3.000001, 3)).toBe(3);
  });
});

describe('trainingProgressPercent', () => {
  it('traite une époque à 0 comme une progression connue, pas comme une absence', () => {
    expect(trainingProgressPercent(0, 3)).toBe(0);
    expect(trainingProgressPercent(0.33, 3)).toBeCloseTo(11, 0);
  });

  it('rend null uniquement quand la progression est réellement inconnue', () => {
    expect(trainingProgressPercent(null, 3)).toBeNull();
    expect(trainingProgressPercent(1, 0)).toBeNull();
  });
});
