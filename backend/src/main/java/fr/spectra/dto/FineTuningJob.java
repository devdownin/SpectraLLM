package fr.spectra.dto;

import java.time.Instant;

public record FineTuningJob(
        String jobId,
        Status status,
        String modelName,
        String baseModel,
        FineTuningRequest parameters,
        int datasetSize,
        String currentStep,
        /**
         * Époques <b>consommées</b>, en valeur fractionnaire ({@code 0.33} = un tiers de la
         * première époque). Le trainer l'émet ainsi à chaque étape ({@code epoch=0.33}) ; la
         * tronquer en entier faisait valoir {@code 0} pendant toute la première époque, et
         * l'interface — qui teste la valeur pour savoir si elle est connue — masquait alors
         * progression, compteur et loss sur le premier tiers d'un run par défaut.
         */
        Double currentEpoch,
        Integer totalEpochs,
        Double loss,
        /**
         * Loss mesurée sur le split de validation à la fin de chaque époque ({@code null} si
         * {@code valSplit = 0}, ou en DPO/ORPO). C'est la seule des deux courbes qui signale un
         * sur-apprentissage : la loss d'entraînement décroît par construction.
         */
        Double evalLoss,
        String outputPath,
        String error,
        /**
         * Phase atteinte au moment de l'échec ({@code null} tant que le job n'a pas échoué).
         *
         * <p>Sans elle, l'échec était un point sans lieu : {@link #failed(String)} écrasait
         * {@code currentStep} par « Échoué » et le statut valait {@code FAILED}, si bien que
         * l'interface plaçait <b>tous</b> les échecs sur la dernière étape — un dataset vide au
         * filtrage s'affichait comme un échec d'import, c'est-à-dire l'inverse de ce qui s'était
         * passé. Elle vaut aussi pour une annulation et pour une interruption au redémarrage :
         * savoir <i>où</i> le travail s'est arrêté est ce qui distingue « rien n'a été calculé »
         * de « l'entraînement était fini, c'est la conversion qui a lâché ».</p>
         */
        Status failedPhase,
        /**
         * Évaluation enchaînée automatiquement après l'enregistrement du modèle ({@code null}
         * si {@code autoEvaluate} était désactivé, ou si le job n'a pas abouti).
         *
         * <p>C'est le lien qui manquait entre les deux moitiés de la chaîne : un job se terminait
         * sur un statut « COMPLETED » sans le moindre indice de <i>qualité</i>, et l'évaluation
         * — pourtant implémentée — vivait sur un autre écran, sans rattachement au job qui avait
         * produit le modèle.</p>
         */
        String evaluationId,
        Instant createdAt,
        Instant completedAt
) {
    public enum Status {
        PENDING, EXPORTING_DATASET, TRAINING, IMPORTING_MODEL, COMPLETED, FAILED,
        /**
         * Arrêté à la demande de l'utilisateur. Distinct de {@link #FAILED} : rien n'est cassé,
         * personne n'a à enquêter. Les confondre — ce que faisait l'annulation, qui écrivait
         * FAILED — présentait un arrêt volontaire comme un incident, badge rouge et toast
         * d'erreur compris.
         */
        CANCELLED;

        /** Vrai pour un état d'où le job ne repartira pas. */
        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    public static FineTuningJob pending(String jobId, FineTuningRequest request) {
        return new FineTuningJob(
                jobId, Status.PENDING, request.modelName(), request.baseModel(),
                request, 0, "En attente", null, request.epochs(),
                null, null, null, null, null, null, Instant.now(), null
        );
    }

    public FineTuningJob withStatus(Status status, String step) {
        return new FineTuningJob(
                jobId, status, modelName, baseModel, parameters, datasetSize,
                step, currentEpoch, totalEpochs, loss, evalLoss, outputPath,
                error, failedPhase, evaluationId, createdAt, completedAt
        );
    }

    /**
     * Progression d'entraînement. Une ligne de sortie ne porte jamais les deux losses à la fois
     * (le trainer logue la training loss par étape et l'eval_loss en fin d'époque) : un argument
     * {@code null} signifie « pas de nouvelle valeur », et la précédente est conservée plutôt
     * qu'effacée.
     *
     * @param epoch époques consommées, fractionnaires ({@code 0.33} = un tiers de la première)
     */
    public FineTuningJob withTrainingProgress(double epoch, Double loss, Double evalLoss) {
        return new FineTuningJob(
                jobId, Status.TRAINING, modelName, baseModel, parameters, datasetSize,
                "Entraînement epoch " + epochInProgress(epoch) + "/" + totalEpochs, epoch, totalEpochs,
                loss != null ? loss : this.loss,
                evalLoss != null ? evalLoss : this.evalLoss,
                outputPath, error, failedPhase, evaluationId, createdAt, completedAt
        );
    }

    /**
     * Numéro de l'époque <b>en cours</b> pour l'affichage : {@code 0.33} époque consommée, c'est
     * la première qui travaille. D'où l'arrondi supérieur — et non la troncature, qui affichait
     * « epoch 0/3 », lisible comme un blocage.
     */
    private int epochInProgress(double epoch) {
        int inProgress = Math.max(1, (int) Math.ceil(epoch));
        return totalEpochs != null ? Math.min(inProgress, totalEpochs) : inProgress;
    }

    public FineTuningJob withDatasetSize(int size) {
        return new FineTuningJob(
                jobId, status, modelName, baseModel, parameters, size,
                currentStep, currentEpoch, totalEpochs, loss, evalLoss, outputPath,
                error, failedPhase, evaluationId, createdAt, completedAt
        );
    }

    public FineTuningJob completed(String outputPath) {
        return new FineTuningJob(
                jobId, Status.COMPLETED, modelName, baseModel, parameters, datasetSize,
                "Terminé", totalEpochs != null ? totalEpochs.doubleValue() : currentEpoch,
                totalEpochs, loss, evalLoss, outputPath, null, null, evaluationId,
                createdAt, Instant.now()
        );
    }

    /**
     * Marque l'échec en <b>retenant la phase où il s'est produit</b>. Un job déjà FAILED conserve
     * sa phase d'origine : la réécrire ferait remonter l'échec à l'endroit du second appel plutôt
     * qu'à celui du premier.
     */
    /**
     * Marque l'arrêt volontaire, en retenant la phase interrompue comme le fait
     * {@link #failed(String)} : « arrêté pendant l'entraînement » et « arrêté avant qu'il ne
     * commence » n'ont pas le même coût.
     */
    public FineTuningJob cancelled(String reason) {
        return new FineTuningJob(
                jobId, Status.CANCELLED, modelName, baseModel, parameters, datasetSize,
                "Arrêté", currentEpoch, totalEpochs, loss, evalLoss, outputPath, reason,
                status.isTerminal() ? failedPhase : status, evaluationId,
                createdAt, Instant.now());
    }

    /**
     * Rattache l'évaluation enchaînée. Appelée <b>après</b> {@link #completed(String)} : le job
     * est déjà terminal, et c'est voulu — l'évaluation dure plusieurs minutes, et faire attendre
     * le statut du job jusqu'à son terme reviendrait à présenter un entraînement réussi comme
     * encore en cours.
     */
    public FineTuningJob withEvaluation(String evaluationId) {
        return new FineTuningJob(
                jobId, status, modelName, baseModel, parameters, datasetSize,
                currentStep, currentEpoch, totalEpochs, loss, evalLoss, outputPath,
                error, failedPhase, evaluationId, createdAt, completedAt
        );
    }

    public FineTuningJob failed(String error) {
        return new FineTuningJob(
                jobId, Status.FAILED, modelName, baseModel, parameters, datasetSize,
                "Échoué", currentEpoch, totalEpochs, loss, evalLoss, outputPath, error,
                status.isTerminal() ? failedPhase : status, evaluationId,
                createdAt, Instant.now()
        );
    }
}
