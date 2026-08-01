package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.InstallationJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class StartupOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(StartupOrchestrator.class);

    /** Dépôt HuggingFace du modèle de chat par défaut, et quantification retenue. */
    private static final String DEFAULT_CHAT_REPO = "bartowski/Qwen2.5-7B-Instruct-GGUF";
    private static final String DEFAULT_QUANTIZATION = "Q4_K_M";
    /** Noms de fichiers de repli — contrôlés par scripts/check-model-defaults.sh. */
    private static final String DEFAULT_CHAT_FILE = "Qwen2.5-7B-Instruct-Q4_K_M.gguf";
    private static final String DEFAULT_EMBED_FILE = "embed.gguf";

    private final SpectraProperties properties;
    private final LlmFitService llmFitService;
    private final Path modelsDir = Path.of("./data/models");

    /**
     * Interrupteur du rattrapage automatique des modèles manquants.
     *
     * <p>Activé par défaut : c'est le comportement attendu d'une installation ordinaire,
     * où l'absence du GGUF doit se résoudre seule. Mais ce rattrapage déclenche un
     * téléchargement de plusieurs gigaoctets sur le seul critère « le fichier n'est pas
     * là » — ce qui est faux dans un contexte de test ou de CI, où le fichier n'a aucune
     * raison d'exister et où la bande passante n'est pas gratuite.</p>
     */
    @Value("${spectra.startup.auto-install-models:true}")
    private boolean autoInstallModels;

    public StartupOrchestrator(SpectraProperties properties, LlmFitService llmFitService) {
        this.properties = properties;
        this.llmFitService = llmFitService;
    }

    @PostConstruct
    public void checkAndInstallMissingModels() {
        if (!autoInstallModels) {
            log.info("Installation automatique des modèles manquants désactivée "
                    + "(spectra.startup.auto-install-models=false)");
            return;
        }
        try {
            Files.createDirectories(modelsDir);

            String chatFile = resolveChatModelFile();
            Path chatPath = modelsDir.resolve(chatFile);
            if (isMissing(chatPath)) {
                // Le rattrapage ne sait produire QUE le modèle par défaut : DEFAULT_CHAT_REPO
                // et DEFAULT_QUANTIZATION sont en dur, et rien ne permet de déduire un dépôt
                // HuggingFace d'un nom de fichier quelconque. Installer quand même reviendrait
                // à télécharger plusieurs gigaoctets d'un AUTRE modèle que celui demandé, puis
                // — autoActivate=true — à l'activer par-dessus le choix de l'utilisateur, sans
                // que le fichier attendu cesse pour autant de manquer.
                if (DEFAULT_CHAT_FILE.equals(chatFile)) {
                    log.info("Modèle de chat par défaut manquant ({}). Installation via le Model Hub…", chatFile);
                    llmFitService.installModel(DEFAULT_CHAT_REPO, DEFAULT_QUANTIZATION, true);
                } else {
                    log.warn("Modèle de chat configuré absent ({}). Aucune installation automatique : "
                            + "le rattrapage ne sait installer que le modèle par défaut ({}). "
                            + "Déposez le fichier dans {} ou installez-le depuis le Model Hub.",
                            chatFile, DEFAULT_CHAT_FILE, modelsDir);
                }
            }

            // Pas d'équivalent pour l'embedding : llmfit ne gère que les modèles de
            // génération. Le GGUF d'embedding est téléchargé par scripts/setup.sh (étape 5/6),
            // et son absence est signalée par le healthcheck de llm-embed. Le code qui tentait
            // ici une installation était spéculatif — il échouait silencieusement et laissait
            // croire à un rattrapage automatique qui n'existait pas.
            Path embedPath = modelsDir.resolve(resolveEmbeddingModelFile());
            if (isMissing(embedPath)) {
                log.warn("Modèle d'embedding absent ({}). Exécutez ./setup.sh pour le télécharger : "
                        + "sans lui, l'ingestion ne peut pas vectoriser les documents.", embedPath);
            }

        } catch (Exception e) {
            log.warn("Erreur lors de la vérification de l'initialisation des modèles au démarrage", e);
        }
    }

    /** Un GGUF sous 1 Mo est un téléchargement interrompu, pas un modèle. */
    private static boolean isMissing(Path path) throws java.io.IOException {
        return !Files.exists(path) || Files.size(path) < 1_048_576;
    }

    /**
     * Nom de FICHIER du GGUF de chat — jamais l'alias du modèle.
     *
     * <p>Le repli lisait {@code effectiveChatModel()}, qui rend l'alias ({@code
     * qwen2.5-7b-instruct}) et non le fichier ({@code Qwen2.5-7B-Instruct-Q4_K_M.gguf}).
     * Résolu en chemin, cet alias ne désigne rien : le modèle était déclaré manquant à
     * CHAQUE démarrage, y compris avec le GGUF bien présent, ce qui déclenchait un
     * téléchargement de plusieurs gigaoctets sans raison. Compose et k8s renseignent tous
     * deux {@code SPECTRA_LLM_CHAT_FILE} et prenaient donc la première branche — le défaut
     * ne se voyait qu'en exécution hors conteneur.
     */
    private String resolveChatModelFile() {
        if (properties.llm().chat() != null && properties.llm().chat().effectiveFile() != null) {
            return properties.llm().chat().effectiveFile();
        }
        return DEFAULT_CHAT_FILE;
    }

    /** Même correction que {@link #resolveChatModelFile()} : un fichier, jamais un alias. */
    private String resolveEmbeddingModelFile() {
        if (properties.llm().embedding() != null && properties.llm().embedding().effectiveFile() != null) {
            return properties.llm().embedding().effectiveFile();
        }
        return DEFAULT_EMBED_FILE;
    }
}
