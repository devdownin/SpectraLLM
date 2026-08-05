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
  | 'FAILED'
  /** Arrêté à la demande de l'utilisateur : rien n'est cassé, personne n'a à enquêter. */
  | 'CANCELLED';

/** Vrai pour un état d'où le job ne repartira pas. */
export const isTerminal = (status: JobStatus): boolean =>
  status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED';

export const PIPELINE_STEPS: { status: Exclude<JobStatus, 'FAILED' | 'CANCELLED'>; icon: string }[] = [
  { status: 'PENDING',           icon: 'hourglass_empty' },
  { status: 'EXPORTING_DATASET', icon: 'dataset' },
  { status: 'TRAINING',          icon: 'model_training' },
  { status: 'IMPORTING_MODEL',   icon: 'upload_file' },
  { status: 'COMPLETED',         icon: 'check_circle' },
];

export type StepState = 'done' | 'active' | 'failed' | 'stopped' | 'todo';

const indexOf = (status: JobStatus): number =>
  PIPELINE_STEPS.findIndex((s) => s.status === status);

/**
 * État de chaque étape, dans l'ordre du pipeline.
 *
 * - `COMPLETED` marque **toutes** les étapes franchies, jalon final compris : un run réussi
 *   affichait quatre étapes allumées suivies d'une pastille grise, comme si la fin n'avait pas
 *   été atteinte.
 * - `FAILED` s'appuie sur `failedPhase`, la phase que le job portait au moment de l'échec : les
 *   étapes qui l'ont précédée sont **franchies**, celle-là est en échec, les suivantes n'ont pas
 *   eu lieu. Sans cette information — jobs antérieurs à son introduction — l'échec ne peut être
 *   situé : on le signale alors sur la dernière étape sans rien marquer comme franchi, plutôt que
 *   d'affirmer un avancement inconnu.
 *
 * @param failedPhase phase atteinte à l'échec ; ignorée hors statut `FAILED`
 */
export function stepStates(status: JobStatus, failedPhase?: JobStatus | null): StepState[] {
  if (status === 'FAILED' || status === 'CANCELLED') {
    // Une phase terminale (donnée incohérente) ne désigne aucune étape : repli sur l'inconnu.
    const known = failedPhase && !isTerminal(failedPhase) ? indexOf(failedPhase) : -1;
    const stoppedAt = known >= 0 ? known : PIPELINE_STEPS.length - 2; // repli : IMPORTING_MODEL
    // Un arrêt volontaire n'est pas un incident : l'étape est marquée « stopped », que l'UI rend
    // en neutre — pas en rouge, qui envoie enquêter sur ce que l'utilisateur a lui-même décidé.
    const mark: StepState = status === 'CANCELLED' ? 'stopped' : 'failed';
    return PIPELINE_STEPS.map((_, i) => {
      if (i === stoppedAt) return mark;
      return known >= 0 && i < stoppedAt ? 'done' : 'todo';
    });
  }

  const current = indexOf(status);
  return PIPELINE_STEPS.map((_, i) => {
    if (status === 'COMPLETED') return 'done';
    if (i < current) return 'done';
    if (i === current) return 'active';
    return 'todo';
  });
}
