package fr.spectra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Context Compression RAG — extraction des passages pertinents à l'intérieur de chaque chunk.
 *
 * <p>Contrairement au Corrective RAG qui filtre des chunks entiers (RELEVANT/IRRELEVANT),
 * ce service extrait uniquement les phrases ou passages directement utiles à l'intérieur de
 * chaque chunk. Résultat : contexte plus dense, moins de bruit, meilleure utilisation de la
 * fenêtre de contexte du modèle — permet d'inclure davantage de sources sans dépasser le budget.</p>
 *
 * <p>Les chunks dont aucun passage n'est pertinent (réponse IRRELEVANT) sont éliminés.
 * En cas d'erreur LLM sur un chunk, le texte original est conservé (fallback sécurisé).</p>
 *
 * <p>Activé via {@code SPECTRA_CONTEXT_COMPRESSION_ENABLED=true}.
 * S'applique après le Corrective RAG et avant la génération (pipelines non-streaming et streaming).</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.context-compression", name = "enabled", havingValue = "true")
public class ContextCompressionService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressionService.class);

    private static final String COMPRESS_SYSTEM =
            "Tu es un extracteur de passages. Réponds UNIQUEMENT avec les phrases extraites telles quelles, ou le mot IRRELEVANT.";

    private static final String COMPRESS_PROMPT = """
            Extrais uniquement les phrases ou passages de ce texte directement pertinents pour répondre à la question.
            Copie les phrases telles quelles, sans reformulation ni ajout.
            Si aucun passage n'est pertinent, réponds uniquement : IRRELEVANT

            Question : %s

            Texte :
            %s

            Passages pertinents :""";

    private final LlmChatClient llmClient;

    public ContextCompressionService(LlmChatClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * Résultat de la compression : textes compressés et indices des chunks conservés.
     * {@code keptIndices.get(i)} correspond à l'index du chunk original pour {@code compressedTexts.get(i)}.
     */
    public record CompressionResult(List<Integer> keptIndices, List<String> compressedTexts) {}

    /**
     * Compresse chaque chunk en extrayant uniquement les passages pertinents à la question.
     * Les chunks jugés entièrement IRRELEVANT sont éliminés (absents de {@code keptIndices}).
     *
     * @param question     question utilisateur (guide l'extraction)
     * @param chunks       chunks récupérés par le retrieval
     * @return {@link CompressionResult} avec indices conservés et textes compressés
     */
    public CompressionResult compress(String question, List<String> chunks) {
        List<Integer> keptIndices    = new ArrayList<>(chunks.size());
        List<String>  compressedTexts = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            String prompt = String.format(COMPRESS_PROMPT, question, chunks.get(i));
            try {
                String result = llmClient.chat(COMPRESS_SYSTEM, prompt).trim();
                if (!result.isBlank() && !result.equalsIgnoreCase("IRRELEVANT")) {
                    keptIndices.add(i);
                    compressedTexts.add(result);
                }
            } catch (Exception e) {
                log.warn("Context compression : chunk {} non compressé, texte original conservé — {}", i, e.getMessage());
                keptIndices.add(i);
                compressedTexts.add(chunks.get(i));
            }
        }

        log.info("Context compression : {} → {} chunks", chunks.size(), keptIndices.size());
        return new CompressionResult(keptIndices, compressedTexts);
    }
}
