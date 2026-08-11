import { describe, it, expect } from 'vitest';
import fr from './fr.json';
import en from './en.json';

/**
 * Les deux locales doivent porter exactement les mêmes clés.
 *
 * <p>Une clé ajoutée d'un seul côté ne casse rien de visible : i18next se rabat sur la langue
 * de secours, et l'écran affiche simplement le texte dans l'autre langue. Personne ne le voit
 * en revue, et le défaut ne se manifeste qu'auprès d'un utilisateur qui, lui, ne signalera pas
 * qu'un libellé est resté en anglais.
 *
 * <p>C'est ce qui a produit le constat F7 : trois pages avaient dérivé — `Playground` comptait
 * 43 chaînes écrites en dur pour 8 appels à `t()`. Ce test empêche la dérive de recommencer.
 */

type Tree = { [key: string]: string | Tree };

function flatten(tree: Tree, prefix = ''): Set<string> {
  const out = new Set<string>();
  for (const [key, value] of Object.entries(tree)) {
    if (typeof value === 'string') out.add(prefix + key);
    else for (const nested of flatten(value, `${prefix}${key}.`)) out.add(nested);
  }
  return out;
}

const frKeys = flatten(fr as Tree);
const enKeys = flatten(en as Tree);

describe('locales', () => {
  it('portent exactement les mêmes clés', () => {
    const manquantEn = [...frKeys].filter((k) => !enKeys.has(k)).sort();
    const manquantFr = [...enKeys].filter((k) => !frKeys.has(k)).sort();

    expect({ manquantEn, manquantFr }).toEqual({ manquantEn: [], manquantFr: [] });
  });

  it('ne laissent aucune traduction vide', () => {
    // Une chaîne vide passe la vérification de parité tout en n'affichant rien : c'est pire
    // qu'une clé absente, puisque le repli sur l'autre langue ne se déclenche même pas.
    const vides: string[] = [];
    const walk = (tree: Tree, locale: string, prefix = '') => {
      for (const [key, value] of Object.entries(tree)) {
        if (typeof value === 'string') {
          if (!value.trim()) vides.push(`${locale}:${prefix}${key}`);
        } else walk(value, locale, `${prefix}${key}.`);
      }
    };
    walk(fr as Tree, 'fr');
    walk(en as Tree, 'en');

    expect(vides).toEqual([]);
  });

  it('gardent les mêmes variables d\'interpolation de part et d\'autre', () => {
    // `{{name}}` oublié dans une traduction affiche « Ingestion de  » avec un trou. i18next ne
    // signale rien : la variable manquante est simplement ignorée.
    const vars = (s: string) => (s.match(/\{\{(\w+)\}\}/g) ?? []).sort().join(',');
    const flatValues = (tree: Tree, prefix = ''): Map<string, string> => {
      const out = new Map<string, string>();
      for (const [key, value] of Object.entries(tree)) {
        if (typeof value === 'string') out.set(prefix + key, value);
        else for (const [k, v] of flatValues(value, `${prefix}${key}.`)) out.set(k, v);
      }
      return out;
    };
    const frV = flatValues(fr as Tree);
    const enV = flatValues(en as Tree);

    const divergentes = [...frV.entries()]
      .filter(([k, v]) => enV.has(k) && vars(v) !== vars(enV.get(k)!))
      .map(([k]) => k)
      .sort();

    expect(divergentes).toEqual([]);
  });
});
