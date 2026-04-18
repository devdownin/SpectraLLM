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

    public record Metadata(
            String source,
            String category,
            String type,
            double confidence
    ) {
    }

    public static TrainingPair of(String instruction, String response, String source, String category, String type, double confidence) {
        return new TrainingPair(
                List.of(
                        new Message("system", "Tu es un assistant spécialisé dans l'exploitation autoroutière."),
                        new Message("user", instruction),
                        new Message("assistant", response)
                ),
                new Metadata(source, category, type, confidence)
        );
    }
}
