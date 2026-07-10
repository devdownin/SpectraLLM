package fr.spectra.dto;

import java.time.Instant;
import java.util.List;

/**
 * Rapport de comparaison directe (A/B head-to-head) entre deux modèles.
 *
 * <p>Pour chaque paire du jeu de test, on génère une réponse de chaque modèle puis
 * un juge désigne la meilleure des deux (ou une égalité). Plus robuste que la
 * notation absolue : le juge compare directement plutôt que de noter dans le vide.
 * L'ordre de présentation des deux réponses est tiré au hasard par paire pour
 * atténuer le biais de position.
 */
public record AbComparisonReport(
        String abId,
        /** PENDING | RUNNING | COMPLETED | FAILED | CANCELLED */
        String status,
        String modelA,
        String modelB,
        /** Modèle ayant servi de juge. */
        String judgeModel,
        int testSetSize,
        int processed,
        int aWins,
        int bWins,
        int ties,
        /** Taux de victoire (parmi les paires jugées) : aWins / processed. */
        double winRateA,
        double winRateB,
        double tieRate,
        List<AbItem> items,
        String error,
        Instant startedAt,
        Instant completedAt
) {

    /** Verdict pour une paire. */
    public record AbItem(
            String question,
            String reference,
            String answerA,
            String answerB,
            /** A | B | TIE */
            String winner,
            String justification,
            String category,
            String source
    ) {}

    public static AbComparisonReport pending(String abId, String modelA, String modelB, String judgeModel) {
        return new AbComparisonReport(
                abId, "PENDING", modelA, modelB, judgeModel,
                0, 0, 0, 0, 0, 0.0, 0.0, 0.0,
                List.of(), null, Instant.now(), null);
    }
}
