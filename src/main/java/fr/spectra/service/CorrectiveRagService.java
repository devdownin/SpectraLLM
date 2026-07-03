package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corrective RAG — évaluation et filtrage de la pertinence des chunks récupérés (I6).
 *
 * <p>Pour chaque chunk extrait du vector store, un grader LLM évalue sa pertinence par rapport
 * à la question : {@code RELEVANT}, {@code AMBIGUOUS} ou {@code IRRELEVANT}.
 * Les chunks {@code IRRELEVANT} sont éliminés avant la génération, ce qui améliore
 * la qualité de la réponse lorsque l'index contient des données bruitées ou mal structurées.</p>
 *
 * <p>Si le nombre de chunks restants tombe en dessous de {@code spectra.corrective-rag.min-relevant-chunks},
 * une reformulation de la requête est tentée pour relancer un retrieval complémentaire
 * via le {@link EmbeddingService} + {@link ChromaDbClient}.</p>
 *
 * <p>Activé via {@code SPECTRA_CORRECTIVE_RAG_ENABLED=true}.</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.corrective-rag", name = "enabled", havingValue = "true")
public class CorrectiveRagService {

    private static final Logger log = LoggerFactory.getLogger(CorrectiveRagService.class);

    private static final String GRADE_SYSTEM =
            "Tu es un évaluateur de pertinence documentaire. Réponds uniquement avec les grades demandés.";

    /**
     * Prompt batch : évalue N chunks en un seul appel LLM.
     * Format attendu en sortie : une ligne par document, ex. "1: RELEVANT\n2: IRRELEVANT\n3: AMBIGUOUS"
     */
    private static final String GRADE_BATCH_PROMPT = """
            Évalue la pertinence de chaque document ci-dessous par rapport à la question.
            Pour chaque document, réponds avec le format "ID: GRADE" où GRADE est exactement \
            RELEVANT, AMBIGUOUS ou IRRELEVANT.

            Question : %s

            %s
            Grades (une ligne par document) :""";

    private static final Pattern GRADE_LINE = Pattern.compile("(\\d+):\\s*(RELEVANT|AMBIGUOUS|IRRELEVANT)",
            Pattern.CASE_INSENSITIVE);

    private final LlmChatClient llmClient;
    private final SpectraProperties props;

    public CorrectiveRagService(LlmChatClient llmClient, SpectraProperties props) {
        this.llmClient = llmClient;
        this.props = props;
    }

    /**
     * Grade chaque chunk et retourne les indices conservés (RELEVANT ou AMBIGUOUS).
     *
     * @param question question utilisateur
     * @param chunks   chunks récupérés par le retrieval
     * @return liste d'indices (0-based) des chunks à conserver
     */
    public List<Integer> gradeChunks(String question, List<String> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        String documentsBlock = buildDocumentsBlock(chunks);
        String prompt = String.format(GRADE_BATCH_PROMPT, question, documentsBlock);

        String response;
        try {
            response = llmClient.chat(GRADE_SYSTEM, prompt);
        } catch (Exception e) {
            log.warn("Corrective RAG : erreur de grading LLM, tous les chunks conservés — {}", e.getMessage());
            return allIndices(chunks.size());
        }

        List<Integer> kept = parseGrades(response, chunks.size());
        log.info("Corrective RAG : {}/{} chunks conservés après grading", kept.size(), chunks.size());
        return kept;
    }

    /**
     * Filtre les listes parallèles (chunks, metadatas, distances, rerankScores, bm25Scores)
     * selon les indices renvoyés par {@link #gradeChunks}.
     *
     * @return {@link FilteredContext} contenant uniquement les éléments des indices conservés
     */
    public FilteredContext filterByIndices(
            List<Integer> keptIndices,
            List<String> chunks,
            List<Map<String, String>> metadatas,
            List<Double> distances,
            List<Float> rerankScores,
            List<Float> bm25Scores) {

        List<String> filteredChunks     = new ArrayList<>(keptIndices.size());
        List<Map<String, String>> filteredMetas = new ArrayList<>(keptIndices.size());
        List<Double> filteredDists      = new ArrayList<>(keptIndices.size());
        List<Float> filteredRerank      = rerankScores != null ? new ArrayList<>(keptIndices.size()) : null;
        List<Float> filteredBm25        = bm25Scores  != null ? new ArrayList<>(keptIndices.size()) : null;

        for (int idx : keptIndices) {
            filteredChunks.add(chunks.get(idx));
            filteredMetas.add(metadatas.get(idx));
            filteredDists.add(distances.get(idx));
            if (filteredRerank != null) filteredRerank.add(rerankScores.get(idx));
            if (filteredBm25   != null) filteredBm25.add(bm25Scores.get(idx));
        }
        return new FilteredContext(filteredChunks, filteredMetas, filteredDists, filteredRerank, filteredBm25);
    }

    /** Seuil de chunks pertinents sous lequel on considère que le retrieval est insuffisant. */
    public int minRelevantChunks() {
        return props.correctiveRag() != null ? props.correctiveRag().effectiveMinRelevantChunks() : 1;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildDocumentsBlock(List<String> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String preview = chunks.get(i).length() > 400 ? chunks.get(i).substring(0, 400) + "..." : chunks.get(i);
            sb.append("Document ").append(i + 1).append(" : ").append(preview).append("\n\n");
        }
        return sb.toString();
    }

    private List<Integer> parseGrades(String response, int total) {
        List<Integer> kept = new ArrayList<>();
        Matcher m = GRADE_LINE.matcher(response);
        boolean[] seen = new boolean[total + 1];
        boolean anyParsed = false;

        while (m.find()) {
            long id;
            try {
                id = Long.parseLong(m.group(1)); // \d+ non borné : éviter NumberFormatException
            } catch (NumberFormatException e) {
                continue;
            }
            String grade = m.group(2).toUpperCase();
            if (id >= 1 && id <= total && !seen[(int) id]) {
                seen[(int) id] = true;
                anyParsed = true;
                if ("RELEVANT".equals(grade) || "AMBIGUOUS".equals(grade)) {
                    kept.add((int) id - 1); // convert to 0-based
                }
            }
        }

        // On ne conserve tout QUE si le grading est illisible (aucune ligne valide parsée).
        // Un grading valide qui note tous les chunks IRRELEVANT doit bien renvoyer une liste vide.
        if (!anyParsed && total > 0) {
            log.warn("Corrective RAG : grading illisible, tous les chunks conservés");
            return allIndices(total);
        }
        return kept;
    }

    private List<Integer> allIndices(int size) {
        List<Integer> all = new ArrayList<>(size);
        for (int i = 0; i < size; i++) all.add(i);
        return all;
    }

    // ── DTO interne ──────────────────────────────────────────────────────────

    public record FilteredContext(
            List<String> chunks,
            List<Map<String, String>> metadatas,
            List<Double> distances,
            List<Float> rerankScores,
            List<Float> bm25Scores
    ) {}
}
