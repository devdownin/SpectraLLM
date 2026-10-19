import type { CitationReport } from '../services/api';

/**
 * Lecture du rapport de vérification des citations — logique pure, sans React.
 *
 * Le rapport mesure la PERTINENCE de chaque phrase citée pour son passage (cross-encoder), pas
 * l'implication logique : un signal de fidélité, pas une preuve. Les seuils de couleur ci-dessous
 * ne font que trier les réponses pour l'œil ; ils ne disent pas qu'une réponse est vraie ou fausse.
 */

export type CitationLevel = 'good' | 'warn' | 'bad';
export type CitationMarkStatus = 'supported' | 'unsupported';

export interface CitationSummary {
  level: CitationLevel;
  supported: number;
  cited: number;
  /** Phrases à examiner : non étayées ou à citation invalide. */
  flagged: number;
  invalid: number;
}

/**
 * Résumé d'un rapport, ou `null` quand il n'y a rien à afficher : pas de rapport (cross-encoder
 * absent) ou aucune phrase citée — un badge « 0/0 » ne dirait rien et ferait croire à un échec.
 */
export const summarizeCitations = (report: CitationReport | null | undefined): CitationSummary | null => {
  if (!report || report.citedSentences <= 0) return null;
  const flagged = report.checks.filter(c => c.status !== 'SUPPORTED').length;
  const ratio = report.supportedSentences / report.citedSentences;
  return {
    level: flagged === 0 ? 'good' : ratio >= 0.5 ? 'warn' : 'bad',
    supported: report.supportedSentences,
    cited: report.citedSentences,
    flagged,
    invalid: report.invalidCitations,
  };
};

/**
 * Statut de chaque numéro `[n]` valide : « étayé » si au moins une phrase qui le cite l'est,
 * « non étayé » si toutes celles qui le citent ne le sont pas. Les numéros hors plage
 * (`max` passages servis) sont ignorés : l'interface ne les rend pas en puces.
 */
export const citationMarks = (
  report: CitationReport | null | undefined,
  max: number,
): Record<number, CitationMarkStatus> => {
  const marks: Record<number, CitationMarkStatus> = {};
  for (const check of report?.checks ?? []) {
    const supported = check.status === 'SUPPORTED';
    for (const n of check.citations) {
      if (n < 1 || n > max) continue;
      if (supported) marks[n] = 'supported';
      else if (marks[n] !== 'supported') marks[n] = 'unsupported';
    }
  }
  return marks;
};
