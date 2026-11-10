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

    private static final String REFORMULATE_SYSTEM =
            "Tu es un expert en reformulation de requêtes de recherche documentaire. Sois concis.";

    private static final String REFORMULATE_PROMPT = """
            La recherche documentaire n'a pas trouvé suffisamment de documents pertinents pour la question suivante.
            Reformule-la pour maximiser les chances de retrouver des documents pertinents \
            (synonymes, termes plus généraux, vocabulaire technique alternatif).
            Réponds UNIQUEMENT avec la question reformulée, sans commentaire.

            Question : %s

            Question reformulée :""";

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
            response = llmClient.decide(GRADE_SYSTEM, prompt);
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

    /**
     * Reformule la question quand le grading laisse moins de {@link #minRelevantChunks()} chunks
     * pertinents, afin de relancer un retrieval complémentaire (cf. {@code RagService}).
     *
     * @return la question reformulée, ou {@link Optional#empty()} si la reformulation échoue
     *         ou n'apporte rien (réponse vide ou identique) — l'appelant conserve alors le
     *         contexte filtré tel quel (dégradation gracieuse)
     */
    public java.util.Optional<String> reformulateQuery(String question) {
        String reformulated;
        try {
            reformulated = llmClient.decide(REFORMULATE_SYSTEM,
                    String.format(REFORMULATE_PROMPT, question)).trim();
        } catch (Exception e) {
            log.warn("Corrective RAG : reformulation échouée — {}", e.getMessage());
            return java.util.Optional.empty();
        }
        if (reformulated.isBlank() || reformulated.equals(question)) {
            return java.util.Optional.empty();
        }
        log.info("Corrective RAG : reformulation {} → {}", fr.spectra.util.LogSafe.text(question),
                fr.spectra.util.LogSafe.text(reformulated));
        return java.util.Optional.of(reformulated);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Extrait de chaque passage soumis au grader ({@code spectra.corrective-rag.preview-chars}). */
    private int previewChars = DEFAULT_PREVIEW_CHARS;

    static final int DEFAULT_PREVIEW_CHARS = 800;
    /** Plancher par passage : en dessous, le grader ne voit plus de quoi juger. */
    static final int MIN_PREVIEW_CHARS = 200;
    /**
     * Taille maximale du bloc de passages du prompt de grading (≈ 2 300 tokens). Ce prompt n'est pas
     * soumis au budget de contexte du RAG : sans borne, relever l'extrait par passage ferait déborder
     * la fenêtre du modèle quand le nombre de passages grimpe (jusqu'à 20).
     */
    static final int MAX_BLOCK_CHARS = 8000;

    @org.springframework.beans.factory.annotation.Autowired
    void configurePreview(
            @org.springframework.beans.factory.annotation.Value("${spectra.corrective-rag.preview-chars:800}") int chars) {
        if (chars < MIN_PREVIEW_CHARS || chars > 4000) {
            throw new IllegalArgumentException("spectra.corrective-rag.preview-chars doit être entre "
                    + MIN_PREVIEW_CHARS + " et 4000");
        }
        this.previewChars = chars;
    }

    /**
     * Les passages, tronqués. Les 400 premiers caractères seulement étaient montrés : un passage dont
     * la partie utile suit un long préambule (en-tête, sommaire, définitions) était jugé sur son début
     * et écarté. L'extrait s'adapte au nombre de passages pour tenir dans {@link #MAX_BLOCK_CHARS}.
     */
    String buildDocumentsBlock(List<String> chunks) {
        int perChunk = chunks.isEmpty() ? previewChars
                : Math.max(MIN_PREVIEW_CHARS, Math.min(previewChars, MAX_BLOCK_CHARS / chunks.size()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String preview = chunks.get(i).length() > perChunk ? chunks.get(i).substring(0, perChunk) + "..." : chunks.get(i);
            sb.append("Document ").append(i + 1).append(" : ").append(preview).append("\n\n");
        }
        return sb.toString();
    }

    private List<Integer> parseGrades(String response, int total) {
        Matcher m = GRADE_LINE.matcher(response);
        boolean[] seen = new boolean[total + 1];
        boolean[] irrelevant = new boolean[total + 1];
        int parsed = 0;

        while (m.find()) {
            long id;
            try {
                id = Long.parseLong(m.group(1)); // \d+ non borné : éviter NumberFormatException
            } catch (NumberFormatException e) {
                continue;
            }
            if (id >= 1 && id <= total && !seen[(int) id]) {
                seen[(int) id] = true;
                parsed++;
                irrelevant[(int) id] = "IRRELEVANT".equalsIgnoreCase(m.group(2));
            }
        }

        // On ne conserve tout QUE si le grading est illisible (aucune ligne valide parsée).
        // Un grading valide qui note tous les chunks IRRELEVANT doit bien renvoyer une liste vide.
        if (parsed == 0 && total > 0) {
            log.warn("Corrective RAG : grading illisible, tous les chunks conservés");
            return allIndices(total);
        }
        // Seul un verdict IRRELEVANT explicite écarte un chunk. Un chunk SANS verdict (sortie
        // tronquée, ligne oubliée par le modèle) était écarté comme s'il avait été jugé hors sujet :
        // une réponse partielle du grader supprimait des passages qu'il n'avait pas lus.
        // L'ordre rendu est celui du classement d'entrée, pas celui des lignes du modèle : la
        // numérotation [n] des passages et la priorité du budget de prompt en dépendent.
        List<Integer> kept = new ArrayList<>();
        for (int id = 1; id <= total; id++) {
            if (!irrelevant[id]) kept.add(id - 1);
        }
        if (parsed < total) {
            log.warn("Corrective RAG : {} chunk(s) sans verdict sur {}, conservés", total - parsed, total);
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
