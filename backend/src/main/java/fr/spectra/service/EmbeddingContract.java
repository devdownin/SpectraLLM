package fr.spectra.service;

import java.util.Map;

/**
 * Contrat d'embedding d'une collection : ce qui doit rester identique entre l'indexation et la
 * requête pour que les vecteurs soient comparables, et la limite que le serveur impose.
 *
 * <ul>
 *   <li>{@code model} — modèle qui a produit les vecteurs (estampille {@code spectra:embedding-model}) ;</li>
 *   <li>{@code prompts} — préfixes d'instruction requête/document (estampille {@code spectra:embedding-prompts}) ;</li>
 *   <li>{@code textVersion} — construction du texte embeddé côté document (estampille
 *       {@code spectra:embedding-text-version}) : 1 = texte brut, 2 = préfixe + en-tête contextuel ;</li>
 *   <li>{@code maxInputTokens} — taille maximale d'un texte embeddé, en tokens <b>du modèle</b> :
 *       le minimum entre la limite de la famille et la fenêtre réellement servie par llm-embed.
 *       Non estampillée : elle ne change pas l'espace vectoriel, seulement ce qu'on peut y écrire.</li>
 * </ul>
 */
public record EmbeddingContract(String model, EmbeddingPromptScheme prompts, int textVersion, int maxInputTokens) {

    /** Version courante de la construction du texte embeddé (préfixe de tâche + en-tête contextuel). */
    public static final int TEXT_VERSION = 2;

    /** Version présumée d'une collection antérieure à l'estampille de version. */
    public static final int LEGACY_TEXT_VERSION = 1;

    /** Fenêtre servie par défaut : llm-embed tourne avec {@code -c 8192 --parallel 4}, soit 2048 par requête. */
    public static final int DEFAULT_SERVER_MAX_INPUT_TOKENS = 2048;

    public EmbeddingContract {
        prompts = EmbeddingPromptScheme.orNone(prompts);
        if (maxInputTokens <= 0) maxInputTokens = DEFAULT_SERVER_MAX_INPUT_TOKENS;
    }

    /** Contrat courant pour un modèle : celui sous lequel une collection neuve ou ré-indexée est écrite. */
    public static EmbeddingContract current(String model, int serverMaxInputTokens) {
        EmbeddingPromptScheme prompts = EmbeddingPromptScheme.detect(model);
        return new EmbeddingContract(model, prompts, TEXT_VERSION,
                Math.min(prompts.maxInputTokens(), serverMaxInputTokens));
    }

    /** Repli quand aucun contrat n'est résolu : aucun préfixe, fenêtre servie par défaut. */
    public static EmbeddingContract orDefault(EmbeddingContract contract) {
        return contract != null ? contract
                : new EmbeddingContract(null, EmbeddingPromptScheme.NONE, TEXT_VERSION, DEFAULT_SERVER_MAX_INPUT_TOKENS);
    }

    /** Texte réellement envoyé au modèle pour un chunk. */
    public String documentText(String text, Map<String, ?> metadata, boolean contextualHeaders) {
        return prompts.document(contextualHeaders ? ContextualHeader.apply(text, metadata) : text);
    }

    /** Les vecteurs de la collection suivent-ils le contrat courant du modèle ? Sinon, ré-indexer. */
    public boolean isCurrentFor(String activeModel) {
        return prompts == EmbeddingPromptScheme.detect(activeModel) && textVersion >= TEXT_VERSION;
    }

    /** Forme lisible, publiée par le rapport de cohérence et le suivi de ré-indexation. */
    public String label() {
        return prompts.name() + "/v" + textVersion;
    }
}
