package fr.spectra.service;

import fr.spectra.dto.ServiceStatus;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Abstraction du provider de chat LLM.
 */
public interface LlmChatClient {

    void setActiveModel(String model);

    String getActiveModel();

    ServiceStatus checkHealth();

    /**
     * Génère une réponse complète (bloquant jusqu'à la fin de la génération).
     * Protégé par un circuit breaker dans les implémentations llama-cpp.
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * Réponse enrichie des statistiques de génération renvoyées par le serveur.
     *
     * @param content          texte généré
     * @param completionTokens nombre de tokens générés (0 si inconnu)
     * @param tokensPerSecond   débit de génération mesuré par le serveur (0 si inconnu)
     */
    record ChatResult(String content, int completionTokens, double tokensPerSecond) {}

    /**
     * Variante de {@link #chat(String, String)} qui remonte aussi les statistiques de
     * génération (tokens, débit) lorsque le provider les expose. L'implémentation par
     * défaut délègue à {@link #chat} sans statistiques (l'appelant retombe alors sur une
     * estimation).
     */
    default ChatResult chatWithStats(String systemPrompt, String userMessage) {
        return new ChatResult(chat(systemPrompt, userMessage), 0, 0.0);
    }

    /**
     * Génère une réponse complète avec paramètres de génération explicites (temperature, top_p).
     * L'implémentation par défaut ignore les paramètres et délègue à {@link #chat(String, String)}.
     */
    default String chat(String systemPrompt, String userMessage, float temperature, float topP) {
        return chat(systemPrompt, userMessage);
    }

    /**
     * Génère une réponse dont la validité JSON est <b>garantie par le décodeur</b>, et non
     * simplement demandée dans le prompt.
     *
     * <p>Sur un backend qui le supporte (llama.cpp, OpenAI…), la génération est contrainte :
     * le modèle ne peut émettre que des tokens formant un objet JSON valide. Cela supprime
     * par construction les préambules en prose, les blocs de réflexion des modèles
     * « reasoning » et les blocs Markdown — autant de causes d'échec qu'il fallait sinon
     * rattraper au parsing, avec le risque d'en oublier une.</p>
     *
     * <p>L'implémentation par défaut retombe sur {@link #chat(String, String, float, float)} :
     * un provider sans décodage contraint reste fonctionnel, l'appelant doit alors continuer
     * de parser défensivement.</p>
     */
    default String chatJson(String systemPrompt, String userMessage, float temperature, float topP) {
        return chat(systemPrompt, userMessage, temperature, topP);
    }

    /** Variante JSON contrainte aux paramètres de génération par défaut (0.7 / 0.9). */
    default String chatJson(String systemPrompt, String userMessage) {
        return chatJson(systemPrompt, userMessage, 0.7f, 0.9f);
    }

    /**
     * Génère une réponse en streaming, token par token.
     * L'implémentation par défaut délègue à {@link #chat} et émet la réponse complète en un seul élément.
     */
    default Flux<String> chatStream(String systemPrompt, String userMessage) {
        return Flux.just(chat(systemPrompt, userMessage));
    }

    /**
     * Streaming avec paramètres de génération explicites (temperature, top_p).
     * L'implémentation par défaut ignore les paramètres et délègue à {@link #chatStream(String, String)}.
     */
    default Flux<String> chatStream(String systemPrompt, String userMessage, float temperature, float topP) {
        return chatStream(systemPrompt, userMessage);
    }

    /**
     * Fenêtre de contexte réellement servie, <b>par requête</b>, telle que le serveur
     * d'inférence la déclare.
     *
     * <p>C'est la seule valeur qui fasse autorité. En déploiement Docker, le backend ne
     * connaît ni {@code LLM_CONTEXT} ni {@code LLM_PARALLEL} — ce sont des variables du
     * conteneur d'inférence — et ne peut donc que la demander. Les budgets de contexte
     * configurés côté backend (extrait de classification, RAG agentique, long-contexte)
     * doivent tenir dedans ; sans cette information ils sont choisis à l'aveugle.</p>
     *
     * <p>Vide quand le provider ne l'expose pas ou que le serveur est injoignable :
     * l'appelant doit alors s'abstenir de conclure plutôt que supposer une valeur.</p>
     */
    default java.util.OptionalInt servedContextTokens() {
        return java.util.OptionalInt.empty();
    }

    List<Map<String, Object>> listModels();

    void createModel(String name, String from, String system, Map<String, Object> params);

    void pullModel(String name);

    boolean hasModel(String name);

    String getDefaultModel();
}
