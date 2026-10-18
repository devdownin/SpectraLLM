package fr.spectra.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Préfixes d'instruction attendus par les modèles d'embedding <b>asymétriques</b>.
 *
 * <p><b>Pourquoi.</b> La plupart des modèles d'embedding récents sont entraînés avec un préfixe
 * distinguant la requête du passage ({@code search_query:} / {@code search_document:} pour
 * nomic-embed-text, {@code query:} / {@code passage:} pour E5…). Sans ce préfixe, requête et
 * passage sont projetés comme deux textes quelconques : le modèle fonctionne, mais en dehors de
 * sa distribution d'entraînement, et le rappel chute — silencieusement. Le modèle par défaut de
 * Spectra (nomic-embed-text v1.5) en fait partie : sa documentation qualifie ces préfixes
 * d'obligatoires.</p>
 *
 * <p><b>Cohérence.</b> Requête et documents d'une même collection doivent suivre le MÊME schéma :
 * une requête préfixée contre des passages qui ne l'étaient pas dégrade autant le classement que
 * l'absence de préfixe. Le schéma est donc estampillé sur chaque collection à sa création
 * ({@link ChromaDbClient#EMBEDDING_PROMPTS_METADATA_KEY}) et c'est l'estampille — non la
 * configuration courante — qui gouverne requêtes et ajouts. Une collection antérieure (sans
 * estampille) reste en {@link #NONE}, exactement comme elle a été indexée ; la ré-indexation
 * ({@code POST /api/config/embedding-consistency/reindex}) la fait passer au schéma du modèle
 * actif.</p>
 */
public enum EmbeddingPromptScheme {

    NONE("", ""),
    /** nomic-embed-text v1 / v1.5 / v2. */
    NOMIC("search_query: ", "search_document: "),
    /** E5 / multilingual-e5. */
    E5("query: ", "passage: "),
    /** BGE v1.5 (en), mxbai-embed-large, snowflake-arctic-embed : instruction côté requête seule. */
    REPRESENT("Represent this sentence for searching relevant passages: ", ""),
    /** Qwen3-Embedding : instruction de tâche côté requête, passages bruts. */
    QWEN3("Instruct: Given a question, retrieve relevant passages that answer the question\nQuery:", "");

    private static final Pattern E5_FAMILY = Pattern.compile("(^|[^a-z0-9])e5([^a-z0-9]|$)");

    private final String queryPrefix;
    private final String documentPrefix;

    EmbeddingPromptScheme(String queryPrefix, String documentPrefix) {
        this.queryPrefix = queryPrefix;
        this.documentPrefix = documentPrefix;
    }

    public String query(String text) {
        return queryPrefix + text;
    }

    public List<String> documents(List<String> texts) {
        return documentPrefix.isEmpty() ? texts : texts.stream().map(t -> documentPrefix + t).toList();
    }

    /** Schéma attendu par un modèle, déduit de son nom (alias du registre ou fichier GGUF). */
    public static EmbeddingPromptScheme detect(String modelName) {
        if (modelName == null || modelName.isBlank()) return NONE;
        String name = modelName.toLowerCase(Locale.ROOT);
        if (name.contains("nomic-embed")) return NOMIC;
        if (name.contains("qwen3-embedding")) return QWEN3;
        if (E5_FAMILY.matcher(name).find()) return E5;
        if (name.contains("mxbai-embed") || name.contains("arctic-embed")) return REPRESENT;
        // bge-m3 et les variantes « bge-*-zh » n'utilisent pas l'instruction anglaise.
        if (name.contains("bge-") && name.contains("-en")) return REPRESENT;
        return NONE;
    }

    /** Lit une estampille de collection ; absente ou inconnue = {@link #NONE} (indexation historique). */
    public static EmbeddingPromptScheme fromStamp(Object stamp) {
        if (stamp == null) return NONE;
        try {
            return valueOf(stamp.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return NONE;
        }
    }

    public static EmbeddingPromptScheme orNone(EmbeddingPromptScheme scheme) {
        return scheme != null ? scheme : NONE;
    }
}
