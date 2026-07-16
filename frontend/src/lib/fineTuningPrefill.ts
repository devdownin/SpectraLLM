/**
 * Préremplissage du formulaire de fine-tuning d'après le modèle LLM actif.
 *
 * Le GGUF actuellement servi n'est PAS ré-entraînable : le champ « modèle de base »
 * doit référencer une base du catalogue (base_models.json) ou un repo HuggingFace.
 * Ces fonctions pures dérivent donc du modèle actif : (1) un nom suggéré pour le
 * modèle fine-tuné, (2) la base entraînable la plus plausible, (3) la règle de
 * remplacement qui garantit qu'une valeur SAISIE par l'utilisateur n'est jamais
 * écrasée. Extraites du composant pour être testables unitairement.
 */

export interface CatalogEntry {
  alias?: string;
  hfRepo?: string;
  description?: string;
}

export interface RegistryEntry {
  name?: string;
  hfRepo?: string;
  parameters?: Record<string, unknown>;
}

/** Minuscules + alphanumérique uniquement, pour comparer alias et noms de modèles. */
const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

/**
 * Nom suggéré pour le modèle fine-tuné, dérivé du modèle actif et conforme au
 * schéma de validation du formulaire (/^[a-z0-9-_]+$/, min. 3 caractères).
 * Ex. « Phi-3 Mini Q4 » → « phi-3-mini-q4-ft ».
 */
export const suggestModelName = (activeModel: string): string => {
  const slug = activeModel.toLowerCase()
    .replace(/[^a-z0-9-_]+/g, '-')
    .replace(/-{2,}/g, '-')
    .replace(/^[-_]+|[-_]+$/g, '');
  return `${slug || 'spectra'}-ft`;
};

/**
 * Base entraînable la plus plausible pour le modèle actif, par ordre de fiabilité :
 * 1. métadonnée `baseModel` de son entrée de registre (modèle déjà fine-tuné → sa
 *    base exacte, seule valide pour re-fusionner un adaptateur LoRA) ;
 * 2. alias du catalogue dont le `hfRepo` correspond à celui de l'entrée ;
 * 3. alias du catalogue contenu dans le nom du modèle actif (ex. « phi3-mini-q4 »
 *    contient « phi3 »).
 * Chaîne vide si rien ne correspond (le champ garde alors sa valeur).
 */
export const resolveTrainableBase = (
  activeModel: string,
  registry: RegistryEntry[],
  catalog: CatalogEntry[],
): string => {
  const entry = registry.find(m => m?.name === activeModel);

  const fromMeta = entry?.parameters?.baseModel;
  if (typeof fromMeta === 'string' && fromMeta.trim()) {
    return fromMeta.trim();
  }
  const byRepo = entry?.hfRepo ? catalog.find(c => c?.hfRepo === entry.hfRepo) : undefined;
  if (byRepo?.alias) {
    return byRepo.alias;
  }
  const byName = catalog.find(c => c?.alias && norm(activeModel).includes(norm(c.alias)));
  return byName?.alias ?? '';
};

/**
 * Une valeur de champ n'est remplacée par une suggestion QUE si c'est un défaut
 * générique (valeur vide ou de repli codée en dur) ou notre propre suggestion
 * précédente (mémorisée en localStorage) : le préremplissage suit le modèle actif
 * sans jamais écraser un choix délibéré de l'utilisateur.
 */
export const shouldReplace = (
  current: string | undefined,
  genericDefault: string,
  previousSuggestion: string | null,
): boolean =>
  !current || current === genericDefault || current === previousSuggestion;
