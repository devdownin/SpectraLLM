package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Confronte les budgets de contexte configurés à la fenêtre <b>réellement servie</b>.
 *
 * <p><b>Pourquoi.</b> Plusieurs réglages du backend décident combien de texte est envoyé au
 * modèle : l'extrait de classification, le budget du RAG agentique, celui du long-contexte.
 * Chacun doit tenir dans la fenêtre d'une requête. Or en déploiement Docker le backend ne
 * connaît ni {@code LLM_CONTEXT} ni {@code LLM_PARALLEL} — ce sont des variables du conteneur
 * d'inférence — et ces budgets sont donc choisis à l'aveugle.</p>
 *
 * <p>Le dépassement ne produit pas une erreur franche : llama.cpp tronque le <b>début</b> de
 * la requête, c'est-à-dire le prompt système. Le modèle perd ses consignes de format et répond
 * hors format ; l'appelant voit un échec de parsing, jamais sa cause. Ce contrôle nomme la
 * cause au démarrage, une fois pour toutes.</p>
 *
 * <p><b>Pourquoi pas un échec au boot.</b> Le serveur d'inférence démarre souvent après l'API
 * (chargement d'un GGUF de plusieurs gigaoctets). Refuser de démarrer parce qu'il n'a pas
 * encore répondu punirait une situation normale : on sonde donc jusqu'à obtenir la valeur,
 * on avertit, puis on se désarme.</p>
 */
@Service
public class ContextBudgetValidator {

    private static final Logger log = LoggerFactory.getLogger(ContextBudgetValidator.class);

    /**
     * Caractères par token, en français. Approximation volontairement pessimiste : le français
     * se tokenize moins bien que l'anglais, et sous-estimer le coût d'un extrait est
     * exactement l'erreur que ce contrôle existe pour attraper.
     */
    static final double CHARS_PER_TOKEN = 3.5;

    /** Coût du prompt d'étiquetage, de son entête et de la réponse attendue (tokens). */
    static final int CLASSIFICATION_OVERHEAD_TOKENS = 510;

    /**
     * Part de la fenêtre au-delà de laquelle un budget est jugé imprudent. On n'attend pas le
     * dépassement strict : la réponse du modèle et les variations de tokenization consomment
     * la marge restante.
     */
    static final double SAFE_FRACTION = 0.85;

    private final LlmChatClient chatClient;
    private final SpectraProperties properties;
    private final AtomicBoolean done = new AtomicBoolean(false);

    public ContextBudgetValidator(LlmChatClient chatClient, SpectraProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /**
     * Sonde la fenêtre servie jusqu'à l'obtenir, valide une fois, puis se désarme.
     * Premier passage laissé tardif : le serveur d'inférence charge son modèle.
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void probeAndValidate() {
        if (done.get()) return;

        OptionalInt served = chatClient.servedContextTokens();
        if (served.isEmpty()) return;   // serveur pas encore prêt : on retentera

        done.set(true);
        int window = served.getAsInt();
        List<String> problems = problems(window, properties);

        if (problems.isEmpty()) {
            log.info("Budgets de contexte compatibles avec la fenêtre servie ({} tokens par requête).", window);
            return;
        }
        log.warn("Fenêtre servie : {} tokens par requête. {} budget(s) trop large(s) — au-delà, "
                + "llama.cpp tronque le DÉBUT de la requête (le prompt système), et le modèle "
                + "répond hors format :", window, problems.size());
        problems.forEach(p -> log.warn("  • {}", p));
        log.warn("  Réduisez ces budgets, ou augmentez LLM_CONTEXT (en gardant à l'esprit qu'il "
                + "est divisé par LLM_PARALLEL).");
    }

    /**
     * Taille d'extrait de classification qui tient réellement dans {@code window}, marge et
     * prompt déduits.
     *
     * <p>Cette méthode est le <b>seul</b> endroit où ce calcul existe : elle sert à la fois à
     * suggérer une valeur dans l'avertissement de démarrage et à borner l'extrait à chaque
     * classification ({@link DocumentClassificationService}). Deux formules distinctes
     * finiraient par diverger, et l'avertissement annoncerait alors une valeur que le code
     * n'applique pas.</p>
     *
     * <p>Le plancher de 500 caractères évite de produire un extrait si court qu'il ne
     * caractérise plus rien : sur une fenêtre à ce point étroite, mieux vaut une
     * classification médiocre et un avertissement lisible qu'un extrait vide.</p>
     */
    static int affordableExcerptChars(int window) {
        // Fenêtre inconnue : affordableContextTokens rend MAX_VALUE, qui n'a pas de sens
        // converti en caractères. Les appelants garantissent window > 0 ; on ne laisse pas
        // pour autant un débordement possible derrière eux.
        if (window <= 0) return 500;
        return Math.max(500, (int) ((affordableContextTokens(window) - CLASSIFICATION_OVERHEAD_TOKENS)
                * CHARS_PER_TOKEN));
    }

    /**
     * Budget de contexte en tokens qui tient dans {@code window}, marge comprise.
     *
     * <p>Pendant du précédent pour les modules qui raisonnent directement en tokens — RAG
     * agentique, RAG long-contexte. Ces deux-là <b>tronquent déjà</b> leur contexte à leur
     * budget configuré ; le défaut n'était pas l'absence de découpe mais le fait de découper
     * à une taille que la fenêtre ne peut pas porter. Un budget de 3000 tokens soigneusement
     * respecté déborde tout autant d'une fenêtre de 2048.</p>
     *
     * <p>{@code window <= 0} (fenêtre inconnue) rend {@link Integer#MAX_VALUE} : l'appelant
     * peut appliquer {@code Math.min} sans condition et conserve alors sa valeur configurée.</p>
     */
    static int affordableContextTokens(int window) {
        if (window <= 0) return Integer.MAX_VALUE;
        return Math.max(256, (int) (window * SAFE_FRACTION));
    }

    /**
     * Budget de contexte effectivement applicable : le minimum entre ce qui est configuré et
     * ce que le serveur peut servir. Sans information du serveur, la valeur configurée passe
     * telle quelle — mieux vaut le comportement demandé qu'une restriction fondée sur une
     * fenêtre supposée.
     */
    static int clampContextTokens(int configured, LlmChatClient chatClient) {
        OptionalInt window = chatClient.servedContextTokens();
        if (window.isEmpty()) return configured;
        return Math.min(configured, affordableContextTokens(window.getAsInt()));
    }

    /**
     * Cœur pur du contrôle : liste les budgets qui ne tiennent pas dans {@code window}.
     * Sans dépendance à Spring ni au réseau, donc directement testable.
     *
     * @param window fenêtre servie par requête, en tokens
     */
    static List<String> problems(int window, SpectraProperties properties) {
        List<String> problems = new ArrayList<>();
        if (window <= 0) return problems;
        int safe = (int) (window * SAFE_FRACTION);

        var classification = properties.classification();
        if (classification != null && classification.isEnabled()) {
            int chars = classification.effectiveMaxExcerptChars();
            int needed = (int) (chars / CHARS_PER_TOKEN) + CLASSIFICATION_OVERHEAD_TOKENS;
            if (needed > safe) {
                problems.add(String.format(
                        "spectra.classification.max-excerpt-chars = %d → ~%d tokens avec le prompt, "
                        + "pour une marge sûre de %d. Valeur tenable : ~%d caractères "
                        + "(appliquée d'office à chaque classification).",
                        chars, needed, safe, affordableExcerptChars(window)));
            }
        }

        var agentic = properties.agenticRag();
        if (agentic != null && agentic.isEnabled()) {
            int budget = agentic.effectiveMaxContextTokens();
            if (budget > safe) {
                problems.add(String.format(
                        "spectra.agentic-rag.max-context-tokens = %d, pour une marge sûre de %d.",
                        budget, safe));
            }
        }

        var longContext = properties.longContextRag();
        if (longContext != null && longContext.isEnabled()) {
            int budget = longContext.effectiveMaxContextTokens();
            if (budget > safe) {
                problems.add(String.format(
                        "spectra.long-context-rag.max-context-tokens = %d, pour une marge sûre de %d.",
                        budget, safe));
            }
        }

        return problems;
    }
}
