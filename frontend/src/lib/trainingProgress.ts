/**
 * Lecture de la progression d'un entraînement : ce que disent les lignes du trainer, et
 * comment elles s'accumulent en série traçable.
 *
 * Extrait de `pages/FineTuning.tsx` pour être testable sans navigateur — ces règles étaient
 * la source de trois défauts de suivi (cf. `docs/process/audit-suivi-finetuning-ui.fr.md`) :
 * la première époque était invisible, la loss de validation était effacée par le sondage, et
 * les losses par étape étaient toutes écrasées dans un unique point par époque.
 */

/** Un point de la courbe. `epoch` est FRACTIONNAIRE : 0.33 = un tiers de la première époque. */
export interface LossPoint {
  epoch: number;
  loss?: number;
  evalLoss?: number;
}

/**
 * Nombre maximal de points conservés. `logging_steps=1` produit une ligne par étape : sur un gros
 * dataset, une série non bornée croîtrait indéfiniment. 2000 points couvrent largement un run
 * lisible à l'écran.
 */
const MAX_POINTS = 2000;

/**
 * Le `(?<!eval_)` est essentiel : sans lui « eval_loss=0.45 » satisfait aussi « loss=… » et la
 * loss de validation est tracée comme loss d'entraînement — masquant l'écart qu'elle mesure.
 */
const TRAIN_LOSS = /(?<!eval_)loss[=:\s]+([0-9]+\.?[0-9]*)/i;
const EVAL_LOSS = /eval_loss[=:\s]+([0-9]+\.?[0-9]*)/i;
/** L'époque est fractionnaire à la source (« epoch=0.33 ») : la tronquer perd le premier tiers. */
const EPOCH = /epoch[=:\s]+([0-9]+\.?[0-9]*)/i;

/**
 * Extrait la progression d'une ligne de sortie du trainer.
 *
 * Format émis par `ProgressLogger` (scripts/train_host.py) :
 * `  epoch=0.33  loss=1.8421` puis, en fin d'époque avec un split de validation,
 * `  epoch=1.00  eval_loss=0.9500`.
 *
 * @returns `null` si la ligne ne porte aucune valeur exploitable.
 */
export function parseProgressLine(
  message: string,
): { epoch: number | null; loss?: number; evalLoss?: number } | null {
  const trainMatch = message.match(TRAIN_LOSS);
  const evalMatch = message.match(EVAL_LOSS);
  if (!trainMatch && !evalMatch) return null;

  const epochMatch = message.match(EPOCH);
  const parsed: { epoch: number | null; loss?: number; evalLoss?: number } = {
    // L'époque de la LIGNE fait foi quand elle est présente : la valeur remontée par le sondage
    // a jusqu'à 4 s de retard et rattacherait la loss à la mauvaise abscisse.
    epoch: epochMatch ? Number.parseFloat(epochMatch[1]) : null,
  };
  if (trainMatch) parsed.loss = Number.parseFloat(trainMatch[1]);
  if (evalMatch) parsed.evalLoss = Number.parseFloat(evalMatch[1]);
  return parsed;
}

/**
 * Charge utile structurée portée par l'évènement lui-même, quand le trainer sait l'émettre
 * (cf. `scripts/spectra_events.py`). Les valeurs y sont **données**, pas devinées.
 */
export interface ProgressPayload {
  epoch: number;
  loss?: number | null;
  evalLoss?: number | null;
}

/**
 * Progression d'une ligne de télémétrie, structurée si l'émetteur l'a fournie, relue dans le
 * texte sinon.
 *
 * Le champ `progress` est la source de vérité : il vient du trainer, qui connaît les valeurs.
 * L'analyse du message reste le **repli** — un `train.sh` antérieur à ce format, ou une image de
 * trainer non reconstruite, n'émet que de la prose et doit continuer d'être suivi.
 *
 * @returns `null` si la ligne ne porte aucune valeur de loss exploitable. Une progression sans
 *   aucune loss n'ouvre pas de point sur la courbe : l'avancement, lui, vient déjà du job.
 */
export function progressOfLogLine(
  line: { message: string; progress?: ProgressPayload | null },
): { epoch: number | null; loss?: number; evalLoss?: number } | null {
  const structured = line.progress;
  if (structured && typeof structured.epoch === 'number') {
    const parsed: { epoch: number | null; loss?: number; evalLoss?: number } = {
      epoch: structured.epoch,
    };
    // `null` et absence se valent ici : le sérialiseur JSON peut produire l'un ou l'autre selon
    // la version du backend, et ni l'un ni l'autre n'est une valeur de loss.
    if (structured.loss != null) parsed.loss = structured.loss;
    if (structured.evalLoss != null) parsed.evalLoss = structured.evalLoss;
    return parsed.loss === undefined && parsed.evalLoss === undefined ? null : parsed;
  }
  return parseProgressLine(line.message);
}

/**
 * Ajoute un point à la série, ou le FUSIONNE dans le dernier point s'il porte la même époque.
 *
 * La fusion (et non le remplacement) est le cœur du correctif S2 : loss d'entraînement et
 * `eval_loss` arrivent sur deux lignes distinctes, et par deux canaux distincts (SSE et sondage
 * REST). Un remplacement effaçait la loss de validation déjà déposée — c'est-à-dire la seule
 * courbe qui signale le sur-apprentissage.
 */
export function mergeLossPoint(series: LossPoint[], point: LossPoint): LossPoint[] {
  const last = series[series.length - 1];
  if (last && last.epoch === point.epoch) {
    const merged = { ...last, ...point };
    return [...series.slice(0, -1), merged];
  }
  // Un point d'époque antérieure au dernier connu (arrivée hors séquence) est ignoré plutôt
  // que d'introduire un repli dans la courbe.
  if (last && point.epoch < last.epoch) return series;
  return [...series.slice(-(MAX_POINTS - 1)), point];
}

/**
 * Numéro de l'époque EN COURS pour l'affichage : 0.33 époque consommée, c'est la première qui
 * travaille. Arrondi supérieur, jamais 0 — « epoch 0/3 » se lit comme un blocage.
 */
export function epochInProgress(currentEpoch: number, totalEpochs: number): number {
  return Math.min(Math.max(1, Math.ceil(currentEpoch)), Math.max(1, totalEpochs));
}

/**
 * Avancement 0..100 de l'entraînement. `null` quand il n'est pas mesurable — et **uniquement**
 * dans ce cas : une époque à `0` est une progression connue (0 %), pas une progression inconnue.
 */
export function trainingProgressPercent(
  currentEpoch: number | null | undefined,
  totalEpochs: number | null | undefined,
): number | null {
  if (currentEpoch == null || !totalEpochs || totalEpochs <= 0) return null;
  return Math.min(100, Math.max(0, (currentEpoch / totalEpochs) * 100));
}
