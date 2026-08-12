import { describe, it, expect } from 'vitest';
import {
  matchesSearch, filterDocuments, sortDocuments,
  availableFormats, availableCategories, groupDocuments,
} from './documentFilters';
import type { IngestedFile } from '../types/api';

/**
 * Ce que ces cas protègent.
 *
 * <p>Ces fonctions vivaient dans quatre `useMemo` de `pages/Documents.tsx` — 1 495 lignes,
 * 16 % de couverture. Aucune n'était testée : elles n'étaient pas atteignables autrement qu'en
 * montant la page entière.
 *
 * <p>Elles décident de ce que l'utilisateur voit. Une règle qui se perd ne provoque aucune
 * erreur : la liste s'affiche, simplement incomplète — le genre de défaut qu'on ne remarque
 * qu'en cherchant un document précis, longtemps après.
 */

const doc = (over: Partial<IngestedFile> = {}): IngestedFile => ({
  sha256: 'a'.repeat(64),
  fileName: 'contrat.pdf',
  format: 'application/pdf',
  chunksCreated: 10,
  ingestedAt: '2026-01-01T00:00:00Z',
  tags: [],
  categories: [],
  lifecycle: 'ACTIVE',
  ...over,
} as IngestedFile);

const NO_FILTER = { search: '', formats: new Set<never>(), qualityMin: 0, category: 'all' } as never;

describe('matchesSearch — la recherche porte sur cinq champs', () => {
  // Retirer l'un d'eux ne vide pas la liste, il la rend incomplète : c'est ce qui rend
  // l'oubli difficile à voir en revue.
  it.each([
    ['le nom de fichier', { fileName: 'rapport-annuel.pdf' }, 'annuel'],
    ['l\'empreinte', { sha256: 'deadbeef' + '0'.repeat(56) }, 'deadbeef'],
    ['une étiquette', { tags: ['rgpd', 'archive'] }, 'rgpd'],
    ['une catégorie', { categories: ['Juridique'] }, 'juridique'],
    ['la collection', { collectionName: 'spectra_rh' }, 'rh'],
  ])('trouve par %s', (_label, over, query) => {
    expect(matchesSearch(doc(over), query)).toBe(true);
  });

  it('ignore la casse', () => {
    expect(matchesSearch(doc({ fileName: 'CONTRAT.pdf' }), 'contrat')).toBe(true);
  });

  it('une recherche vide ne filtre rien', () => {
    expect(matchesSearch(doc(), '')).toBe(true);
  });

  it('ne trouve pas ce qui n\'est nulle part', () => {
    expect(matchesSearch(doc(), 'introuvable')).toBe(false);
  });
});

describe('filterDocuments', () => {
  it('« unclassified » filtre SUR l\'absence de catégorie, ce n\'est pas « pas de filtre »', () => {
    // Le piège du fichier : `all` et `unclassified` sont deux valeurs de la même variable,
    // l'une désactivant le filtre et l'autre l'activant sur un critère inverse.
    const classe = doc({ sha256: '1', categories: ['Juridique'] });
    const nonClasse = doc({ sha256: '2', categories: [] });

    const res = filterDocuments([classe, nonClasse], { ...NO_FILTER, category: 'unclassified' });

    expect(res.map(d => d.sha256)).toEqual(['2']);
  });

  it('« all » laisse tout passer, classé ou non', () => {
    const res = filterDocuments(
      [doc({ sha256: '1', categories: ['X'] }), doc({ sha256: '2' })],
      { ...NO_FILTER, category: 'all' },
    );
    expect(res).toHaveLength(2);
  });

  it('un score de qualité absent vaut zéro face à un seuil', () => {
    // Sans ce défaut, un document jamais évalué passerait tous les seuils et polluerait
    // une liste censée ne montrer que le corpus de qualité.
    const sansScore = doc({ sha256: '1', qualityScore: undefined });
    const bon = doc({ sha256: '2', qualityScore: 0.9 });

    const res = filterDocuments([sansScore, bon], { ...NO_FILTER, qualityMin: 0.5 });

    expect(res.map(d => d.sha256)).toEqual(['2']);
  });

  it('un seuil à zéro ne filtre pas', () => {
    const res = filterDocuments([doc({ qualityScore: undefined })], { ...NO_FILTER, qualityMin: 0 });
    expect(res).toHaveLength(1);
  });

  it('un ensemble de formats vide laisse passer tous les formats', () => {
    const res = filterDocuments([doc({ fileName: 'a.pdf' }), doc({ fileName: 'b.txt' })], NO_FILTER);
    expect(res).toHaveLength(2);
  });

  it('cumule les critères, sans en oublier un', () => {
    const cible = doc({ sha256: '1', fileName: 'bail.pdf', categories: ['Juridique'], qualityScore: 0.8 });
    const mauvaiseCategorie = doc({ sha256: '2', fileName: 'bail.pdf', categories: ['RH'], qualityScore: 0.8 });
    const scoreTropBas = doc({ sha256: '3', fileName: 'bail.pdf', categories: ['Juridique'], qualityScore: 0.2 });

    const res = filterDocuments([cible, mauvaiseCategorie, scoreTropBas], {
      ...NO_FILTER, search: 'bail', qualityMin: 0.5, category: 'Juridique',
    });

    expect(res.map(d => d.sha256)).toEqual(['1']);
  });
});

describe('sortDocuments', () => {
  const a = doc({ sha256: '1', fileName: 'beta.pdf', chunksCreated: 5, qualityScore: 0.2, ingestedAt: '2026-01-01T00:00:00Z' });
  const b = doc({ sha256: '2', fileName: 'alpha.pdf', chunksCreated: 50, qualityScore: 0.9, ingestedAt: '2026-06-01T00:00:00Z' });

  it('par nom, alphabétiquement', () => {
    expect(sortDocuments([a, b], 'name').map(d => d.fileName)).toEqual(['alpha.pdf', 'beta.pdf']);
  });

  it('par chunks, décroissant', () => {
    expect(sortDocuments([a, b], 'chunks').map(d => d.sha256)).toEqual(['2', '1']);
  });

  it('par qualité, décroissant, un score absent en dernier', () => {
    const sansScore = doc({ sha256: '3', qualityScore: undefined });
    expect(sortDocuments([sansScore, a, b], 'quality').map(d => d.sha256)).toEqual(['2', '1', '3']);
  });

  it('par défaut, du plus récemment ingéré au plus ancien', () => {
    expect(sortDocuments([a, b], 'date' as never).map(d => d.sha256)).toEqual(['2', '1']);
  });

  it('ne mute pas le tableau reçu', () => {
    // La liste vient du cache React Query : la trier en place corromprait le cache d'autres
    // écrans. L'ancien code s'en tirait parce qu'il triait le résultat de `filter`.
    const source = [a, b];
    sortDocuments(source, 'name');
    expect(source.map(d => d.sha256)).toEqual(['1', '2']);
  });
});

describe('availableFormats / availableCategories', () => {
  it('ne propose que les formats réellement présents', () => {
    const formats = availableFormats([doc({ fileName: 'a.pdf' }), doc({ fileName: 'b.pdf' })]);
    expect(formats).toHaveLength(1);
  });

  it('complète la taxonomie par les catégories rencontrées', () => {
    // Cas réel : la taxonomie a changé après une première classification. Sans la complétion,
    // les documents portant l'ancienne catégorie deviendraient infiltrables.
    const cats = availableCategories([doc({ categories: ['Ancienne'] })], ['Juridique', 'RH']);
    expect(cats).toEqual(['Ancienne', 'Juridique', 'RH']);
  });

  it('n\'invente rien quand la taxonomie est absente', () => {
    expect(availableCategories([doc({ categories: ['X'] })], undefined)).toEqual(['X']);
  });

  it('dédoublonne', () => {
    const cats = availableCategories([doc({ categories: ['X'] }), doc({ categories: ['X'] })], ['X']);
    expect(cats).toEqual(['X']);
  });
});

describe('groupDocuments', () => {
  it('« none » ne produit aucun groupe', () => {
    expect(groupDocuments([doc()], 'none')).toEqual({});
  });

  it('regroupe par cycle de vie', () => {
    const groups = groupDocuments(
      [doc({ sha256: '1', lifecycle: 'ACTIVE' }), doc({ sha256: '2', lifecycle: 'ARCHIVED' })],
      'lifecycle',
    );
    expect(Object.keys(groups).sort()).toHaveLength(2);
  });

  it('place plusieurs documents dans le même groupe', () => {
    const groups = groupDocuments(
      [doc({ sha256: '1', lifecycle: 'ACTIVE' }), doc({ sha256: '2', lifecycle: 'ACTIVE' })],
      'lifecycle',
    );
    expect(Object.values(groups)[0]).toHaveLength(2);
  });
});
