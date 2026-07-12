package fr.spectra.service;

import fr.spectra.dto.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordination des bascules du modèle de chat actif, partagée par tous les harnais
 * d'évaluation ({@link EvaluationService}, {@link QualityBenchmarkService},
 * {@link RagAblationService}).
 *
 * <p><b>Verrou global.</b> Le modèle actif est un état global (un seul llama-server de
 * chat) : deux passages concurrents qui basculent chacun le modèle se le voleraient
 * mutuellement et produiraient des mesures croisées. Chaque service se protégeait de
 * lui-même (verrou privé) mais pas des autres — ce bean unique porte désormais LE verrou
 * ({@link #lock()}/{@link #unlock()}, réentrant et équitable) que tout harnais doit tenir
 * pendant un passage qui bascule le modèle.</p>
 *
 * <p><b>Attente de convergence.</b> En mode conteneurs séparés (défaut Docker),
 * {@code setActiveModel} ne fait qu'écrire le pointeur du registre : le superviseur de
 * llm-chat le relit (~10 s) puis recharge llama-server (jusqu'à plusieurs minutes selon le
 * GGUF). Pendant cette fenêtre, le serveur sert <b>encore l'ancien modèle</b> — llama-server
 * ignore le champ {@code model} de la requête — et les réponses seraient silencieusement
 * attribuées au mauvais modèle. {@link #activate(String)} bloque donc jusqu'à ce que le
 * serveur confirme servir le modèle demandé (contrat {@code activeModelLoaded} de
 * {@link LlmChatClient#checkHealth()}), avec un timeout configurable. En mode orchestrateur
 * embarqué, la bascule est déjà bloquante : la première vérification suffit.</p>
 */
@Service
public class ModelSwitchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ModelSwitchCoordinator.class);

    private final LlmChatClient chatClient;
    /** Équitable : les harnais en attente passent dans l'ordre d'arrivée. */
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Duration convergenceTimeout;
    private final Duration pollInterval;

    public ModelSwitchCoordinator(
            LlmChatClient chatClient,
            @Value("${spectra.llm.switch.convergence-timeout-seconds:300}") long convergenceTimeoutSeconds,
            @Value("${spectra.llm.switch.poll-interval-millis:2000}") long pollIntervalMillis) {
        this.chatClient = chatClient;
        this.convergenceTimeout = Duration.ofSeconds(Math.max(0, convergenceTimeoutSeconds));
        this.pollInterval = Duration.ofMillis(Math.max(1, pollIntervalMillis));
    }

    /** Prend le verrou global de bascule (bloquant, équitable, réentrant). */
    public void lock() {
        lock.lock();
    }

    /** Relâche le verrou global de bascule. */
    public void unlock() {
        lock.unlock();
    }

    /**
     * Bascule le modèle actif puis <b>attend</b> que le serveur le serve réellement.
     * À appeler sous {@link #lock()} avant toute mesure attribuée à {@code model}.
     *
     * @throws IllegalArgumentException alias inconnu du registre (rejeté par la bascule)
     * @throws IllegalStateException    modèle non servable, ou non servi avant le timeout
     */
    public void activate(String model) {
        chatClient.setActiveModel(model);
        awaitServed(model);
    }

    /**
     * Attend que {@link LlmChatClient#checkHealth()} confirme que le modèle actif est servi
     * ({@code available} = {@code activeModelLoaded}). Pendant un rechargement, le serveur
     * est injoignable ou sert encore l'ancien alias : on continue d'interroger jusqu'au
     * timeout ({@code spectra.llm.switch.convergence-timeout-seconds}).
     *
     * @throws IllegalStateException si le modèle n'est pas servi avant le timeout
     */
    void awaitServed(String model) {
        long deadline = System.nanoTime() + convergenceTimeout.toNanos();
        while (true) {
            ServiceStatus health = checkHealthSafely();
            if (health != null && health.available()) {
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Le modèle '" + model + "' est actif dans le registre mais le serveur LLM ne le sert "
                        + "toujours pas après " + convergenceTimeout.toSeconds() + " s (statut: "
                        + (health != null ? health.version() : "indisponible") + "). En mode conteneurs "
                        + "séparés, vérifiez que llm-chat suit le pointeur du registre et que l'alias "
                        + "correspond au modèle chargé avec '-a' ; le timeout est réglable via "
                        + "spectra.llm.switch.convergence-timeout-seconds.");
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrompu en attendant que le modèle '" + model + "' soit servi", e);
            }
        }
    }

    /**
     * Restaure le modèle initialement actif, en <b>best-effort</b> et sans attente de
     * convergence : un échec de restauration (fichier disparu, serveur indisponible) ne doit
     * jamais détruire le rapport que l'appelant vient de calculer — d'où le catch intégral,
     * contrairement à {@link #activate(String)}.
     */
    public void restore(String original) {
        try {
            if (original != null && !original.isBlank() && !original.equals(chatClient.getActiveModel())) {
                chatClient.setActiveModel(original);
                log.info("Modèle actif restauré → {}", original);
            }
        } catch (Exception e) {
            log.warn("Impossible de restaurer le modèle actif '{}' : {}", original, e.getMessage());
        }
    }

    private ServiceStatus checkHealthSafely() {
        try {
            return chatClient.checkHealth();
        } catch (Exception e) {
            log.debug("checkHealth en échec pendant l'attente de convergence : {}", e.getMessage());
            return null;
        }
    }
}
