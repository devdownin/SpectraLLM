package fr.spectra.model;

import java.util.List;
import java.util.Map;

/**
 * Paire d'entraînement au format conversation multi-tour.
 */
public record TrainingPair(
        List<Message> conversations,
        Metadata metadata
) {
    public record Message(String role, String content) {
    }

    /**
     * @param category         nature de la paire elle-même ({@code qa}, {@code summary},
     *                         {@code negative}, ou la catégorie prédite pour une paire de
     *                         classification)
     * @param documentCategory catégorie <b>du document source</b>, telle qu'attribuée par le
     *                         classifieur GED (R8) ; {@code null} si le document n'est pas
     *                         classifié. Distincte de {@code category} : elle décrit d'où vient
     *                         la paire, pas ce qu'elle enseigne — c'est elle qui permet de
     *                         doser ou d'exclure un thème du corpus d'entraînement.
     */
    public record Metadata(
            String source,
            String category,
            String type,
            double confidence,
            String documentCategory
    ) {
    }

    /** Paire sans rattachement thématique connu (document non classifié). */
    public static TrainingPair of(String instruction, String response, String source, String category, String type, double confidence) {
        return of(instruction, response, source, category, type, confidence, null);
    }

    public static TrainingPair of(String instruction, String response, String source,
                                  String category, String type, double confidence,
                                  String documentCategory) {
        return new TrainingPair(
                List.of(
                        new Message("system", AssistantPersona.SYSTEM_PROMPT),
                        new Message("user", instruction),
                        new Message("assistant", response)
                ),
                new Metadata(source, category, type, confidence, documentCategory)
        );
    }

    /**
     * Paire <b>ancrée</b> : le prompt système est exactement celui du service RAG — persona,
     * consignes de citation, bloc de passages numérotés — et la question reste nue.
     *
     * <p>C'est la correction du constat F14. {@link #of} produit un prompt court sans contexte,
     * alors que le modèle est ensuite servi avec un contexte long et une consigne de citation
     * qu'il n'a jamais vue. Le SFT enseignait donc à répondre <i>de mémoire, sans citer</i> —
     * l'inverse de ce que la production demande. Une part du dataset construite sous cette forme
     * est le seul moyen d'apprendre au modèle à <i>utiliser</i> le contexte : c'est le bénéfice
     * attendu du couplage RAG + fine-tuning revendiqué par le produit.
     *
     * @param contextBlock passages numérotés, produits par
     *                     {@link RagPromptFormat#contextBlock(List)}
     */
    public static TrainingPair grounded(String question, String response, String contextBlock,
                                        String source, String category, String type,
                                        double confidence, String documentCategory) {
        return new TrainingPair(
                List.of(
                        new Message("system", RagPromptFormat.systemPrompt(
                                AssistantPersona.SYSTEM_PROMPT, contextBlock)),
                        new Message("user", question),
                        new Message("assistant", response)
                ),
                new Metadata(source, category, type, confidence, documentCategory)
        );
    }
}
