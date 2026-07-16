import { describe, it, expect } from 'vitest';
import { resolveTrainableBase, shouldReplace, suggestModelName } from './fineTuningPrefill';

/**
 * Préremplissage du formulaire de fine-tuning d'après le modèle actif : résolution
 * de la base ENTRAÎNABLE (le GGUF servi ne l'est pas — le backend le rejette) et
 * règle « ne jamais écraser une saisie utilisateur ».
 */

const catalog = [
  { alias: 'tinyllama', hfRepo: 'TinyLlama/TinyLlama-1.1B-Chat-v1.0' },
  { alias: 'phi3', hfRepo: 'microsoft/Phi-3-mini-4k-instruct' },
  { alias: 'mistral', hfRepo: 'mistralai/Mistral-7B-Instruct-v0.3' },
];

describe('suggestModelName', () => {
  it('dérive un nom conforme au schéma /^[a-z0-9-_]+$/ depuis le modèle actif', () => {
    expect(suggestModelName('Phi-3 Mini Q4')).toBe('phi-3-mini-q4-ft');
    expect(suggestModelName('llama3.2:3b')).toBe('llama3-2-3b-ft');
    expect(suggestModelName('TheBloke/Llama-2-7B')).toBe('thebloke-llama-2-7b-ft');
  });

  it('replie sur « spectra-ft » quand le nom actif ne laisse rien d’exploitable', () => {
    expect(suggestModelName('***')).toBe('spectra-ft');
    expect(suggestModelName('')).toBe('spectra-ft');
  });

  it('ne produit ni tiret de tête/queue ni tirets doublés', () => {
    expect(suggestModelName('--mon modèle--')).toBe('mon-mod-le-ft');
  });
});

describe('resolveTrainableBase', () => {
  it('priorité 1 : la métadonnée baseModel du registre (modèle déjà fine-tuné)', () => {
    const registry = [
      { name: 'mon-ft', hfRepo: 'microsoft/Phi-3-mini-4k-instruct', parameters: { baseModel: 'tinyllama' } },
    ];
    // La métadonnée gagne même si hfRepo correspond à un AUTRE alias du catalogue :
    // un adaptateur LoRA n'est fusionnable que sur la base exacte qui l'a entraîné.
    expect(resolveTrainableBase('mon-ft', registry, catalog)).toBe('tinyllama');
  });

  it('priorité 2 : correspondance hfRepo entre le registre et le catalogue', () => {
    const registry = [{ name: 'phi3-mini-4k-q8', hfRepo: 'microsoft/Phi-3-mini-4k-instruct' }];
    expect(resolveTrainableBase('phi3-mini-4k-q8', registry, catalog)).toBe('phi3');
  });

  it('priorité 3 : alias du catalogue contenu dans le nom du modèle actif', () => {
    // Pas d'entrée de registre : seule la correspondance de nom peut jouer.
    expect(resolveTrainableBase('mistral-7b-instruct-q4_k_m', [], catalog)).toBe('mistral');
    // La normalisation ignore ponctuation et casse : « Phi-3 » contient « phi3 ».
    expect(resolveTrainableBase('Phi-3-mini-Q4', [], catalog)).toBe('phi3');
  });

  it('renvoie une chaîne vide quand rien ne correspond (le champ garde sa valeur)', () => {
    expect(resolveTrainableBase('qwen2.5-7b-instruct', [], catalog)).toBe('');
    expect(resolveTrainableBase('qwen2.5-7b-instruct', [{ name: 'qwen2.5-7b-instruct' }], catalog)).toBe('');
  });

  it('ignore une métadonnée baseModel vide et retombe sur les correspondances', () => {
    const registry = [{ name: 'actif', hfRepo: 'mistralai/Mistral-7B-Instruct-v0.3', parameters: { baseModel: '  ' } }];
    expect(resolveTrainableBase('actif', registry, catalog)).toBe('mistral');
  });

  it('tolère un catalogue et un registre vides ou mal formés', () => {
    expect(resolveTrainableBase('actif', [], [])).toBe('');
    expect(resolveTrainableBase('actif', [{} as never], [{} as never])).toBe('');
  });
});

describe('shouldReplace — ne jamais écraser une saisie utilisateur', () => {
  it('remplace un champ vide ou le défaut générique codé en dur', () => {
    expect(shouldReplace('', 'spectra-domain', null)).toBe(true);
    expect(shouldReplace(undefined, 'spectra-domain', null)).toBe(true);
    expect(shouldReplace('spectra-domain', 'spectra-domain', null)).toBe(true);
  });

  it('remplace la suggestion précédente (le préremplissage suit le modèle actif)', () => {
    expect(shouldReplace('phi3-mini-q4-ft', 'spectra-domain', 'phi3-mini-q4-ft')).toBe(true);
  });

  it('ne remplace JAMAIS une valeur saisie par l’utilisateur', () => {
    expect(shouldReplace('mon-nom-a-moi', 'spectra-domain', 'phi3-mini-q4-ft')).toBe(false);
    expect(shouldReplace('mon-nom-a-moi', 'spectra-domain', null)).toBe(false);
  });
});
