package fr.spectra.service.training;

import java.nio.file.Path;

/**
 * Paramètres d'une fusion LoRA → GGUF.
 *
 * @param jobId       identifiant du job, pour la corrélation des logs et l'annulation
 * @param adapterPath adaptateur LoRA produit par l'entraînement
 * @param outputDir   répertoire de sortie du modèle fusionné
 * @param modelName   nom du modèle enrichi
 * @param baseHfRepo  repo HuggingFace résolu — <b>doit être exactement</b> celui qui a entraîné
 *                    l'adaptateur, un LoRA n'étant fusionnable que sur sa base d'origine
 */
public record ExportSpec(
        String jobId,
        Path adapterPath,
        Path outputDir,
        String modelName,
        String baseHfRepo) {
}
