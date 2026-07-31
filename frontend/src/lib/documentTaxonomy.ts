/**
 * Classement et regroupement des documents de la GED — logique pure, sans React.
 *
 * Ces fonctions vivaient dans `Documents.tsx`, où elles n'étaient ni exportées ni
 * atteignables par un test : vérifier qu'un `.docx` est bien reconnu supposait de monter
 * la page entière. Elles décident pourtant de ce que l'utilisateur voit — l'icône, le
 * libellé, le groupe — et leur cascade de conditions est exactement le genre de code où
 * l'ordre des tests compte (`.doc` après `.docx`, sinon le second n'est jamais atteint).
 */

import type { DocumentLifecycle, IngestedFile } from '../types/api';
import type { BadgeTone } from '../components/ui';

export type DocumentTypeKey = 'pdf' | 'json' | 'xml' | 'docx' | 'doc' | 'txt' | 'avro' | 'other';
export type SortMode = 'recent' | 'name' | 'chunks' | 'quality';
export type GroupBy = 'none' | 'type' | 'lifecycle' | 'collection';

export interface DocumentTypeMeta {
  key: DocumentTypeKey;
  label: string;
  icon: string;
  accentClass: string;
}

export const DOCUMENT_TYPES: Record<DocumentTypeKey, DocumentTypeMeta> = {
  pdf:   { key: 'pdf',   label: 'PDF',   icon: 'picture_as_pdf', accentClass: 'text-error border-error/20 bg-error/5' },
  json:  { key: 'json',  label: 'JSON',  icon: 'data_object',    accentClass: 'text-primary border-primary/20 bg-primary/5' },
  xml:   { key: 'xml',   label: 'XML',   icon: 'code_blocks',    accentClass: 'text-secondary border-secondary/20 bg-secondary/5' },
  docx:  { key: 'docx',  label: 'DOCX',  icon: 'description',    accentClass: 'text-primary border-primary/20 bg-primary/5' },
  doc:   { key: 'doc',   label: 'DOC',   icon: 'article',        accentClass: 'text-primary border-primary/20 bg-primary/5' },
  txt:   { key: 'txt',   label: 'TXT',   icon: 'notes',          accentClass: 'text-on-surface-variant border-outline-variant/20 bg-surface-container-high' },
  avro:  { key: 'avro',  label: 'AVRO',  icon: 'schema',         accentClass: 'text-secondary border-secondary/20 bg-secondary/5' },
  other: { key: 'other', label: 'OTHER', icon: 'draft',          accentClass: 'text-on-surface-variant border-outline-variant/20 bg-surface-container-high' },
};

export const LIFECYCLE_COLORS: Record<DocumentLifecycle, string> = {
  INGESTED: 'border-outline-variant/30 text-outline',
  QUALIFIED: 'border-secondary/40 text-secondary bg-secondary/5',
  TRAINED: 'border-primary/40 text-primary bg-primary/5',
  ARCHIVED: 'border-on-surface-variant/20 text-on-surface-variant bg-surface-container-low',
};

/** Tonalité Badge par état du cycle de vie (chips d'affichage). */
export const LIFECYCLE_TONES: Record<DocumentLifecycle, BadgeTone> = {
  INGESTED: 'neutral',
  QUALIFIED: 'secondary',
  TRAINED: 'primary',
  ARCHIVED: 'neutral',
};

export const LIFECYCLE_BAR_COLORS: Record<string, string> = {
  INGESTED: 'bg-outline-variant',
  QUALIFIED: 'bg-secondary',
  TRAINED: 'bg-primary',
  ARCHIVED: 'bg-on-surface-variant/30',
};

export const QUALITY_THRESHOLDS = [
  { label: null,    value: 0 },
  { label: '≥ 25%', value: 0.25 },
  { label: '≥ 50%', value: 0.50 },
  { label: '≥ 75%', value: 0.75 },
];

/**
 * Type d'un document, déduit de son format MIME **ou** de son extension.
 *
 * <p><b>L'ordre des conditions porte du sens</b>, et il était faux. Le type MIME officiel
 * d'un DOCX est
 * `application/vnd.openxmlformats-officedocument.wordprocessingml.document` : il
 * <b>contient la sous-chaîne `xml`</b>. Comme la condition XML était évaluée avant celle du
 * DOCX, tout document Word moderne était classé comme du XML — mauvaise icône, mauvais
 * libellé, mauvais groupe. Le défaut a survécu parce que cette fonction vivait dans un
 * composant de 1 500 lignes, hors d'atteinte du moindre test.</p>
 *
 * <p>Les formats les plus spécifiques sont donc testés d'abord. Pour la même raison,
 * `.docx` précède `.doc` : `.doc` est un suffixe de `.docx`, et la condition la plus large
 * capturerait les deux.</p>
 */
export function getDocumentType(file: IngestedFile): DocumentTypeMeta {
  const format = file.format.toLowerCase();
  const name = file.fileName.toLowerCase();
  if (name.endsWith('.docx') || format.includes('officedocument.wordprocessingml')) return DOCUMENT_TYPES.docx;
  if (name.endsWith('.doc') || format.includes('msword')) return DOCUMENT_TYPES.doc;
  if (format.includes('json') || name.endsWith('.json')) return DOCUMENT_TYPES.json;
  if (format.includes('xml') || name.endsWith('.xml')) return DOCUMENT_TYPES.xml;
  if (format.includes('pdf') || name.endsWith('.pdf')) return DOCUMENT_TYPES.pdf;
  if (format.includes('avro') || name.endsWith('.avro')) return DOCUMENT_TYPES.avro;
  if (format.includes('text/plain') || name.endsWith('.txt')) return DOCUMENT_TYPES.txt;
  return DOCUMENT_TYPES.other;
}

/** Clé de regroupement d'un document selon le critère choisi ('' si aucun regroupement). */
export function getGroupKey(doc: IngestedFile, groupBy: GroupBy): string {
  if (groupBy === 'type') return getDocumentType(doc).key;
  if (groupBy === 'lifecycle') return doc.lifecycle;
  if (groupBy === 'collection') return doc.collectionName ?? '—';
  return '';
}

/** Libellé affiché pour une clé de groupe. */
export function getGroupLabel(key: string, groupBy: GroupBy): string {
  if (groupBy === 'type') return DOCUMENT_TYPES[key as DocumentTypeKey]?.label ?? key.toUpperCase();
  return key;
}
