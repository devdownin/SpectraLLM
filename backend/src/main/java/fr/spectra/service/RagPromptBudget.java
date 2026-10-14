package fr.spectra.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Budget du prompt complet : rôles/instructions/question/historique/passages et réserve de sortie. */
final class RagPromptBudget {
    static final int RESPONSE_RESERVE_TOKENS = 500;
    static final int MESSAGE_OVERHEAD_TOKENS = 32;
    // Borne de secours explicite quand le provider ne publie pas sa fenêtre.
    static final int UNKNOWN_WINDOW_TOKENS = 3000;

    private RagPromptBudget() {}

    /** Sélection dans l'ordre du retrieval ; un passage trop grand n'empêche pas les suivants de tenir. */
    static List<Integer> selectIndices(int candidateCount, LlmChatClient client, int configuredLimit,
                                      Function<List<Integer>, String> renderFullPrompt) {
        var served = client.servedContextTokens();
        int window = served.isPresent() && served.getAsInt() > 0
                ? served.getAsInt() : UNKNOWN_WINDOW_TOKENS;
        int allowance = Math.min(configuredLimit, (int) (window * ContextBudgetValidator.SAFE_FRACTION))
                - RESPONSE_RESERVE_TOKENS - MESSAGE_OVERHEAD_TOKENS;
        if (allowance <= 0 || TokenEstimator.estimateTokens(renderFullPrompt.apply(List.of())) > allowance) {
            throw new IllegalArgumentException("Les instructions et la question dépassent le budget du modèle ; "
                    + "réduisez la question ou l'historique de conversation.");
        }
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < candidateCount; i++) {
            List<Integer> trial = new ArrayList<>(selected);
            trial.add(i);
            if (TokenEstimator.estimateTokens(renderFullPrompt.apply(trial)) <= allowance) {
                selected.add(i);
            }
        }
        return List.copyOf(selected);
    }
}
