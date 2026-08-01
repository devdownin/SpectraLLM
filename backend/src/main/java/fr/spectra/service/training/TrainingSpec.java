package fr.spectra.service.training;

import java.nio.file.Path;

/**
 * Paramètres d'un entraînement, nommés.
 *
 * <p><b>Ce que ce record remplace.</b> {@code FineTuningService} passait onze arguments
 * <b>positionnels</b> à {@code train.sh}, dont l'ordre n'était documenté que par un commentaire
 * dans l'en-tête du script. Insérer un paramètre au mauvais rang décalait silencieusement tous
 * les suivants : {@code epochs} devenait le taux d'apprentissage, {@code packing} devenait
 * {@code dpo}. Rien n'échouait — l'entraînement tournait, sur d'autres hyperparamètres que ceux
 * demandés. C'est le constat F10 de {@code audit-finetuning.fr.md}, refermé par construction ici.
 *
 * <p>La traduction en arguments de ligne de commande devient la responsabilité du
 * {@link TrainingRunner}, seul endroit où l'ordre compte encore — et le seul à connaître le
 * script qu'il invoque.
 *
 * @param jobId         identifiant du job, pour la corrélation des logs et l'annulation
 * @param datasetFile   JSONL d'entraînement
 * @param adapterPath   répertoire de sortie de l'adaptateur LoRA
 * @param baseHfRepo    repo HuggingFace <b>déjà résolu</b> depuis le catalogue — jamais un alias
 * @param loraRank      rang de la décomposition LoRA
 * @param loraAlpha     facteur d'échelle LoRA
 * @param epochs        nombre de passes sur le jeu de données
 * @param learningRate  taux d'apprentissage
 * @param packing       concaténation des séquences courtes (multipacking)
 * @param dpo           alignement DPO
 * @param orpo          alignement ORPO (SFT + préférence en une passe)
 * @param valSplit      fraction réservée à la validation (0 = aucune)
 */
public record TrainingSpec(
        String jobId,
        Path datasetFile,
        Path adapterPath,
        String baseHfRepo,
        int loraRank,
        int loraAlpha,
        int epochs,
        double learningRate,
        boolean packing,
        boolean dpo,
        boolean orpo,
        double valSplit) {
}
