import { getDocumentType, getGroupKey } from './documentTaxonomy';
import type { DocumentTypeKey, GroupBy, SortMode } from './documentTaxonomy';
import type { IngestedFile } from '../types/api';

/**
 * Filtrage, tri et regroupement de la liste de documents.
 *
 * <p>Ces fonctions vivaient dans quatre `useMemo` au milieu de `pages/Documents.tsx` — 1 495
 * lignes, 180 fonctions, 16 % de couverture. C'est le constat F4 de l'audit frontend : la
 * logique loge là où elle est la plus coûteuse à tester.
 *
 * <p>Elles n'ont pourtant rien de spécifique à React : ce sont des fonctions **pures**, données
 * en entrée, données en sortie. Les sortir du composant est donc un déplacement à comportement
 * identique — le seul type d'extraction qu'on puisse faire sans filet — et il les rend
 * testables une par une, ce qu'elles n'étaient pas.
 *
 * <p>Le filtre est plus subtil qu'il n'y paraît : la recherche porte sur **cinq** champs, la
 * catégorie a un cas spécial (`unclassified` ≠ « pas de filtre »), et le seuil de qualité traite
 * un score absent comme un zéro. Autant de règles qu'aucun test ne fixait.
 */

export interface DocumentFilter {
  /** Recherche libre ; chaîne vide = pas de filtre. */
  search: string;
  /** Ensemble vide = tous les formats. */
  formats: Set<DocumentTypeKey>;
  /** Score minimal ; 0 = pas de filtre. */
  qualityMin: number;
  /** `all`, `unclassified`, ou le nom d'une catégorie. */
  category: string;
}

/**
 * La recherche porte sur cinq champs : nom, empreinte, étiquettes, catégories, collection.
 *
 * <p>Un utilisateur qui colle un sha256 doit retrouver son document, et celui qui tape le nom
 * d'une étiquette aussi. En retirer un se remarque très tard — la liste n'est pas vide, elle
 * est seulement incomplète.
 */
export function matchesSearch(doc: IngestedFile, search: string): boolean {
  if (!search) return true;
  const q = search.toLowerCase();
  return doc.fileName.toLowerCase().includes(q)
      || doc.sha256.toLowerCase().includes(q)
      || doc.tags.some((t) => t.toLowerCase().includes(q))
      || (doc.categories ?? []).some((c) => c.toLowerCase().includes(q))
      || (doc.collectionName ?? '').toLowerCase().includes(q);
}

export function filterDocuments(documents: IngestedFile[], filter: DocumentFilter): IngestedFile[] {
  return (documents ?? []).filter((doc) => {
    if (!matchesSearch(doc, filter.search)) return false;
    if (filter.formats.size > 0 && !filter.formats.has(getDocumentType(doc).key)) return false;
    // Un score absent vaut zéro : sans cela, un document non évalué passerait tous les seuils.
    if (filter.qualityMin > 0 && (doc.qualityScore ?? 0) < filter.qualityMin) return false;
    if (filter.category === 'unclassified') {
      // « Non classé » n'est pas l'absence de filtre : c'est un filtre sur l'absence de catégorie.
      return (doc.categories ?? []).length === 0;
    }
    if (filter.category !== 'all' && !(doc.categories ?? []).includes(filter.category)) return false;
    return true;
  });
}

/**
 * Tri, sur une **copie** : trier en place muterait le tableau reçu, et la liste d'origine vient
 * du cache React Query. L'ancien code s'en tirait parce qu'il triait le résultat de `filter`,
 * donc un tableau neuf ; séparer les deux fonctions rendait ce détail dangereux.
 */
export function sortDocuments(documents: IngestedFile[], mode: SortMode): IngestedFile[] {
  return [...documents].sort((a, b) => {
    if (mode === 'name') return a.fileName.localeCompare(b.fileName);
    if (mode === 'chunks') return b.chunksCreated - a.chunksCreated;
    if (mode === 'quality') return (b.qualityScore ?? 0) - (a.qualityScore ?? 0);
    return Date.parse(b.ingestedAt) - Date.parse(a.ingestedAt);
  });
}

/** Formats réellement présents dans le corpus, triés — pas la liste théorique. */
export function availableFormats(documents: IngestedFile[]): DocumentTypeKey[] {
  const seen = new Set<DocumentTypeKey>();
  (documents ?? []).forEach((doc) => seen.add(getDocumentType(doc).key));
  return Array.from(seen).sort();
}

/**
 * Catégories proposées au filtre : la taxonomie configurée, complétée par celles réellement
 * rencontrées dans le corpus.
 *
 * <p>La complétion n'est pas cosmétique : elle couvre la taxonomie ouverte, et le cas d'une
 * taxonomie modifiée après une première classification — sans quoi les documents portant une
 * ancienne catégorie deviendraient infiltrables.
 */
export function availableCategories(documents: IngestedFile[], taxonomy?: string[]): string[] {
  const seen = new Set<string>(taxonomy ?? []);
  (documents ?? []).forEach((doc) => (doc.categories ?? []).forEach((c) => seen.add(c)));
  return Array.from(seen).sort();
}

/** Regroupement ; `none` ne produit aucun groupe, l'appelant affiche alors la liste à plat. */
export function groupDocuments(
  documents: IngestedFile[],
  groupBy: GroupBy,
): Record<string, IngestedFile[]> {
  if (groupBy === 'none') return {};
  return documents.reduce((acc, doc) => {
    const key = getGroupKey(doc, groupBy);
    (acc[key] ??= []).push(doc);
    return acc;
  }, {} as Record<string, IngestedFile[]>);
}
