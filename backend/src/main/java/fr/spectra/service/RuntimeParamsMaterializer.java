package fr.spectra.service;

import fr.spectra.dto.ResourceProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Matérialise les paramètres llama-server recommandés dans le volume des modèles.
 *
 * <p><b>Pourquoi.</b> Le dimensionnement (threads, contexte, batch, KV cache) était
 * implémenté deux fois : en Java ({@link ResourceAdvisorService}, mode embarqué) et en
 * bash ({@code llama-autostart.sh}, ~100 lignes dupliquées). Ce composant fait du Java
 * l'unique propriétaire du calcul en mode compose : les recommandations sont écrites dans
 * {@code data/models/active-chat-params}, que l'entrypoint superviseur de llm-chat
 * ({@code scripts/llm-chat-entrypoint.sh}) consomme comme <b>valeurs par défaut</b> —
 * un {@code LLM_*} explicite dans {@code .env} garde toujours la priorité.</p>
 *
 * <p><b>Limite assumée.</b> La détection <b>GPU</b> n'est pas matérialisée : le conteneur
 * spectra-api ne voit pas les GPU attribués à llm-chat (pas de {@code nvidia-smi}). Elle
 * reste locale au conteneur qui sert le modèle — offload via {@code LLM_CHAT_EXTRA_ARGS}
 * (override compose GPU) ou {@code llama-autostart.sh} (images autonomes k8s/GKE). Les
 * hints CPU/RAM restent valides : les deux conteneurs partagent le même hôte.</p>
 */
@Service
public class RuntimeParamsMaterializer {

    private static final Logger log = LoggerFactory.getLogger(RuntimeParamsMaterializer.class);
    /** Nom du fichier de hints dans le volume des modèles (contrat avec l'entrypoint). */
    static final String PARAMS_FILE = "active-chat-params";

    private final ResourceAdvisorService resourceAdvisor;
    private final Path modelsDir;

    public RuntimeParamsMaterializer(ResourceAdvisorService resourceAdvisor,
                                     @Value("${llmfit.models-dir:./data/models}") String modelsDirPath) {
        this.resourceAdvisor = resourceAdvisor;
        this.modelsDir = Path.of(modelsDirPath);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        materialize();
    }

    /** (Ré)écrit le fichier de hints depuis le profil de ressources courant (best-effort). */
    public synchronized void materialize() {
        try {
            ResourceProfile.LlamaServerParams chat = resourceAdvisor.getProfile().chatRecommendation();
            String content = """
                    # Généré par spectra-api (ResourceAdvisorService) — NE PAS ÉDITER À LA MAIN.
                    # Valeurs PAR DÉFAUT consommées par scripts/llm-chat-entrypoint.sh ;
                    # un LLM_* explicite dans .env garde la priorité. Régénéré au démarrage
                    # de l'API et via POST /api/config/resources/refresh.
                    RECO_THREADS=%d
                    RECO_CONTEXT=%d
                    RECO_BATCH=%d
                    RECO_CACHE_TYPE_K=%s
                    RECO_CACHE_TYPE_V=%s
                    """.formatted(chat.threads(), chat.contextSize(), chat.batchSize(),
                    chat.cacheTypeK(), chat.cacheTypeV());

            Files.createDirectories(modelsDir);
            Path tmp = modelsDir.resolve(PARAMS_FILE + ".tmp");
            Files.writeString(tmp, content);
            try {
                Files.move(tmp, modelsDir.resolve(PARAMS_FILE),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, modelsDir.resolve(PARAMS_FILE), StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Paramètres llama-server recommandés matérialisés → {} (threads={}, ctx={}, batch={})",
                    modelsDir.resolve(PARAMS_FILE), chat.threads(), chat.contextSize(), chat.batchSize());
        } catch (Exception e) {
            log.warn("Impossible de matérialiser les paramètres recommandés : {}", e.getMessage());
        }
    }
}
