/**
 * Étapes du pipeline de fine-tuning et état de chacune pour un job donné.
 *
 * Extrait de `StepBar` : la règle d'affichage tenait en trois expressions ternaires imbriquées
 * dans le JSX, donc intestable — et l'une d'elles était fausse (le jalon final n'était jamais
 * allumé, cf. constat S6 de `docs/process/audit-suivi-finetuning-ui.fr.md`).
 */

export type JobStatus =
  | 'PENDING'
  | 'EXPORTING_DATASET'
  | 'TRAINING'
  | 'IMPORTING_MODEL'
  | 'COMPLETED'
  | 'FAILED';

export const PIPELINE_STEPS: { status: Exclude<JobStatus, 'FAILED'>; icon: string }[] = [
  { status: 'PENDING',           icon: 'hourglass_empty' },
  { status: 'EXPORTING_DATASET', icon: 'dataset' },
  { status: 'TRAINING',          icon: 'model_training' },
  { status: 'IMPORTING_MODEL',   icon: 'upload_file' },
  { status: 'COMPLETED',         icon: 'check_circle' },
];

export type StepState = 'done' | 'active' | 'failed' | 'todo';

const indexOf = (status: JobStatus): number =>
  PIPELINE_STEPS.findIndex((s) => s.status === status);

/**
 * État de chaque étape, dans l'ordre du pipeline.
 *
 * - `COMPLETED` marque **toutes** les étapes franchies, jalon final compris : un run réussi
 *   affichait quatre étapes allumées suivies d'une pastille grise, comme si la fin n'avait pas
 *   été atteinte.
 * - `FAILED` ne peut pointer l'étape réellement fautive : le backend écrase `currentStep` par
 *   « Échoué » et ne conserve pas la phase atteinte (constat S5, à traiter avec le lot suivant).
 *   En attendant, l'échec est signalé sur l'étape d'import — et **aucune étape n'est marquée
 *   franchie**, pour ne pas affirmer un avancement qu'on ne connaît pas.
 */
export function stepStates(status: JobStatus): StepState[] {
  if (status === 'FAILED') {
    const failedAt = PIPELINE_STEPS.length - 2; // IMPORTING_MODEL
    return PIPELINE_STEPS.map((_, i) => (i === failedAt ? 'failed' : 'todo'));
  }

  const current = indexOf(status);
  return PIPELINE_STEPS.map((_, i) => {
    if (status === 'COMPLETED') return 'done';
    if (i < current) return 'done';
    if (i === current) return 'active';
    return 'todo';
  });
}
