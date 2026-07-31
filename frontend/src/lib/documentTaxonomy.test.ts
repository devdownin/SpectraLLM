import { describe, expect, it } from 'vitest';
import {
  DOCUMENT_TYPES,
  getDocumentType,
  getGroupKey,
  getGroupLabel,
} from './documentTaxonomy';
import type { IngestedFile } from '../types/api';

/**
 * Ces fonctions vivaient à l'intérieur de `Documents.tsx`, non exportées : vérifier qu'un
 * `.docx` est bien reconnu supposait de monter la page entière, personne ne l'a fait.
 * Elles décident pourtant de l'icône, du libellé et du groupe que voit l'utilisateur.
 */

const file = (fileName: string, format: string, extra: Partial<IngestedFile> = {}) =>
  ({ fileName, format, lifecycle: 'INGESTED', ...extra } as IngestedFile);

describe('getDocumentType', () => {
  it('reconnaît le type par le format MIME', () => {
    expect(getDocumentType(file('sans-extension', 'application/pdf'))).toBe(DOCUMENT_TYPES.pdf);
    expect(getDocumentType(file('sans-extension', 'application/json'))).toBe(DOCUMENT_TYPES.json);
    expect(getDocumentType(file('sans-extension', 'text/plain'))).toBe(DOCUMENT_TYPES.txt);
  });

  it("reconnaît le type par l'extension quand le format ne dit rien", () => {
    expect(getDocumentType(file('rapport.pdf', 'application/octet-stream'))).toBe(DOCUMENT_TYPES.pdf);
    expect(getDocumentType(file('flux.avro', 'application/octet-stream'))).toBe(DOCUMENT_TYPES.avro);
  });

  it('distingue .docx de .doc — l\'ordre des conditions en dépend', () => {
    // « .doc » est un suffixe de « .docx » : tester le plus court d'abord classerait
    // tous les documents Word modernes comme des anciens.
    expect(getDocumentType(file('note.docx', ''))).toBe(DOCUMENT_TYPES.docx);
    expect(getDocumentType(file('note.doc', ''))).toBe(DOCUMENT_TYPES.doc);
    expect(getDocumentType(file('note', 'application/msword'))).toBe(DOCUMENT_TYPES.doc);
    expect(
      getDocumentType(file('note', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')),
    ).toBe(DOCUMENT_TYPES.docx);
  });

  it('ignore la casse du nom comme du format', () => {
    expect(getDocumentType(file('RAPPORT.PDF', 'APPLICATION/OCTET-STREAM'))).toBe(DOCUMENT_TYPES.pdf);
  });

  it('retombe sur « other » plutôt que de planter', () => {
    expect(getDocumentType(file('archive.zip', 'application/zip'))).toBe(DOCUMENT_TYPES.other);
    expect(getDocumentType(file('', ''))).toBe(DOCUMENT_TYPES.other);
  });
});

describe('getGroupKey', () => {
  it('groupe par type, cycle de vie ou collection', () => {
    const doc = file('a.pdf', 'application/pdf', {
      lifecycle: 'TRAINED',
      collectionName: 'corpus-a',
    });

    expect(getGroupKey(doc, 'type')).toBe('pdf');
    expect(getGroupKey(doc, 'lifecycle')).toBe('TRAINED');
    expect(getGroupKey(doc, 'collection')).toBe('corpus-a');
  });

  it('remplace une collection absente par un tiret, pas par « undefined »', () => {
    expect(getGroupKey(file('a.pdf', ''), 'collection')).toBe('—');
  });

  it('ne groupe pas quand le critère est « none »', () => {
    expect(getGroupKey(file('a.pdf', ''), 'none')).toBe('');
  });
});

describe('getGroupLabel', () => {
  it('affiche le libellé du type, pas sa clé technique', () => {
    expect(getGroupLabel('docx', 'type')).toBe('DOCX');
  });

  it('rend lisible une clé de type inconnue au lieu de rien afficher', () => {
    expect(getGroupLabel('parquet', 'type')).toBe('PARQUET');
  });

  it('rend la clé telle quelle pour les autres critères', () => {
    expect(getGroupLabel('TRAINED', 'lifecycle')).toBe('TRAINED');
    expect(getGroupLabel('corpus-a', 'collection')).toBe('corpus-a');
  });
});
