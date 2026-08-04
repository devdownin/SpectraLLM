package fr.spectra.config;

import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.LlmChatClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Publie la disponibilité du serveur de chat dans {@code /actuator/health}.
 *
 * <p>Sans cet indicateur, la stack pouvait être « healthy » de bout en bout alors que
 * llama-server finissait de charger un GGUF de plusieurs gigaoctets : {@code spectra-api}
 * n'attend que ChromaDB, {@code frontend} n'attend que {@code spectra-api}, et les serveurs
 * LLM sont volontairement indépendants (leurs entrypoints attendent le modèle en boucle au
 * lieu d'échouer). L'interface le signalait déjà — {@code ServiceHealthBanner} interroge
 * {@code /api/status} — mais rien ne l'exposait à qui exploite la stack en ligne de commande,
 * par {@code docker inspect} ou depuis une supervision.
 *
 * <p><b>Cet indicateur ne renvoie jamais DOWN.</b> Deux raisons :
 * <ol>
 *   <li>Un modèle en cours de chargement est un état de démarrage NORMAL, pas une panne.
 *       Un DOWN ferait basculer le statut agrégé de {@code /actuator/health}, c'est-à-dire
 *       précisément ce sur quoi une supervision déclenche une alerte.</li>
 *   <li>{@code UNKNOWN} est le dernier de l'ordre d'agrégation par défaut
 *       ({@code DOWN, OUT_OF_SERVICE, UP, UNKNOWN}) : il ne peut pas dégrader l'agrégat.</li>
 * </ol>
 *
 * <p><b>Il ne participe pas non plus au groupe {@code readiness}</b>, qui ne contient que
 * {@code readinessState} faute de {@code management.endpoint.health.group} déclaré. C'est
 * volontaire et il faut que ça le reste : le healthcheck Compose de {@code spectra-api} vise
 * {@code /actuator/health/readiness}, et {@code frontend} dépend de {@code service_healthy}.
 * Y faire entrer le LLM rendrait le démarrage du frontend tributaire du chargement du modèle
 * — la dépendance bloquante que la suppression de {@code model-init} a justement retirée.
 */
@Component
public class LlmChatHealthIndicator implements HealthIndicator {

    private final LlmChatClient llmChatClient;

    public LlmChatHealthIndicator(LlmChatClient llmChatClient) {
        this.llmChatClient = llmChatClient;
    }

    @Override
    public Health health() {
        // checkHealth() est borné (timeout de 5 s) et capture ses exceptions : il rend
        // « unavailable » plutôt que de lever. L'appel ne peut donc pas faire échouer
        // — ni suspendre indéfiniment — une requête sur /actuator/health.
        ServiceStatus status = llmChatClient.checkHealth();

        Health.Builder health = status.available() ? Health.up() : Health.status(Status.UNKNOWN);
        health.withDetail("url", status.url())
                .withDetail("elapsedMs", status.elapsedMs());

        // On ne recopie PAS status.details() en bloc : il embarque le catalogue des modèles
        // enregistrés et l'état du runtime, hors sujet ici et coûteux à sérialiser à chaque
        // sonde. Seul l'essentiel pour décider « puis-je poser une question ? ».
        Object activeModel = status.details().get("activeModel");
        if (activeModel != null) {
            health.withDetail("activeModel", activeModel);
        }
        health.withDetail("state", describe(status));
        return health.build();
    }

    /**
     * Distingue les deux façons de ne pas être prêt, qui n'appellent pas la même action :
     * un serveur injoignable relève de l'infrastructure, un modèle non chargé se résout
     * en attendant — ou en déposant le GGUF attendu.
     */
    private String describe(ServiceStatus status) {
        if (status.available()) {
            return "ready";
        }
        return "model-not-loaded".equals(status.version()) ? "loading" : "unreachable";
    }
}
