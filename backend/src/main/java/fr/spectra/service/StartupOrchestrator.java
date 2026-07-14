package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.InstallationJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class StartupOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(StartupOrchestrator.class);

    private final SpectraProperties properties;
    private final LlmFitService llmFitService;
    private final Path modelsDir = Path.of("./data/models");

    public StartupOrchestrator(SpectraProperties properties, LlmFitService llmFitService) {
        this.properties = properties;
        this.llmFitService = llmFitService;
    }

    @PostConstruct
    public void checkAndInstallMissingModels() {
        try {
            Files.createDirectories(modelsDir);

            String chatFile = properties.llm().effectiveChatModel();
            if (properties.llm().chat() != null && properties.llm().chat().effectiveFile() != null) {
                chatFile = properties.llm().chat().effectiveFile();
            }
            if (chatFile == null) chatFile = "Phi-4-mini-reasoning-UD-IQ1_S.gguf";

            Path chatPath = modelsDir.resolve(chatFile);
            if (!Files.exists(chatPath) || Files.size(chatPath) < 1048576) {
                log.info("Modèle de chat par défaut manquant ({}). Déclenchement de l'installation via le Model Hub...", chatFile);
                // Le format est `unsloth/Phi-4-mini-reasoning-GGUF`
                // Mais llmfit attend un nom de modèle connu dans son catalogue.
                // Assuming it can handle huggingface repos if we pass the right ID.
                llmFitService.installModel("unsloth/Phi-4-mini-reasoning-GGUF", "Q4_K_M", true);
            }

            // Embedding model logic would go here if llmfit supports it.
            // Actuellement llmfit est plutôt centré chat/generation. Si llmfit gère
            // nomic-embed-text, on fait pareil.
            String embedFile = properties.llm().effectiveEmbeddingModel();
            if (properties.llm().embedding() != null && properties.llm().embedding().effectiveFile() != null) {
                embedFile = properties.llm().embedding().effectiveFile();
            }
            if (embedFile == null) embedFile = "embed.gguf";
            Path embedPath = modelsDir.resolve(embedFile);
            if (!Files.exists(embedPath) || Files.size(embedPath) < 1048576) {
                log.info("Modèle d'embedding par défaut manquant ({}). Déclenchement de l'installation via le Model Hub...", embedFile);
                llmFitService.installModel("nomic-ai/nomic-embed-text-v1.5-GGUF", "Q4_K_M", false);
            }

        } catch (Exception e) {
            log.warn("Erreur lors de la vérification de l'initialisation des modèles au démarrage", e);
        }
    }
}
