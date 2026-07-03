package fr.spectra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.dto.FineTuningRequest;
import fr.spectra.dto.QueryRequest;
import fr.spectra.persistence.ArticleCommentEntity;
import fr.spectra.persistence.ArticleCommentRepository;
import fr.spectra.persistence.IngestedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Gestion des commentaires d'articles avec génération RAG et export DPO.
 *
 * <p>Stratégie optimale RAG + fine-tuning :
 * <ol>
 *   <li>Génération : le RAG récupère les chunks pertinents du document, le LLM
 *       produit un commentaire ancré dans ce contexte.</li>
 *   <li>Évaluation : l'utilisateur note chaque commentaire IA (APPROVED / REJECTED).</li>
 *   <li>Export : les paires (focus-prompt, commentaire approuvé, commentaire rejeté)
 *       constituent un jeu de données DPO prêt à l'emploi pour le fine-tuning.</li>
 *   <li>Re-entraînement automatique : quand le nombre de commentaires approuvés
 *       atteint un multiple du seuil configuré, un job DPO est soumis automatiquement.</li>
 * </ol>
 */
@Service
public class ArticleCommentService {

    private static final Logger log = LoggerFactory.getLogger(ArticleCommentService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Seuil de similarité Jaccard au-dessus duquel un rejet synthétique est considéré trop proche du chosen. */
    private static final double SIMILARITY_THRESHOLD = 0.85;

    private static final String COMMENT_SYSTEM_PROMPT = """
            Tu es un expert en analyse documentaire.
            Rédige un commentaire analytique et structuré sur le document fourni.
            Le commentaire doit :
            - Identifier les points clés et les thèmes principaux
            - Souligner les implications pratiques ou opérationnelles
            - Rester factuel et basé uniquement sur le contenu fourni
            - Être rédigé de manière professionnelle et concise (150-300 mots)
            Ne fabrique pas d'information absente du contexte.
            """;

    private final ArticleCommentRepository commentRepo;
    private final IngestedFileRepository fileRepo;
    private final RagService ragService;
    private final LlmChatClient chatClient;
    private final FineTuningService fineTuningService;
    private final Path dpoExportPath;
    private final int autoRetrainThreshold;

    public ArticleCommentService(ArticleCommentRepository commentRepo,
                                 IngestedFileRepository fileRepo,
                                 RagService ragService,
                                 LlmChatClient chatClient,
                                 @Lazy FineTuningService fineTuningService,
                                 @Value("${spectra.dataset.dir:./data/dataset}") String datasetDir,
                                 @Value("${spectra.ged.auto-retrain-threshold:5}") int autoRetrainThreshold) {
        this.commentRepo = commentRepo;
        this.fileRepo = fileRepo;
        this.ragService = ragService;
        this.chatClient = chatClient;
        this.fineTuningService = fineTuningService;
        this.dpoExportPath = Path.of(datasetDir).resolve("comments_dpo.jsonl");
        this.autoRetrainThreshold = autoRetrainThreshold;
    }

    // ── Lecture ───────────────────────────────────────────────────────────────

    public List<ArticleCommentEntity> getComments(String documentSha256) {
        return commentRepo.findByDocumentSha256OrderByCreatedAtDesc(documentSha256);
    }

    // ── Ajout manuel (commentaire humain) ────────────────────────────────────

    public ArticleCommentEntity addHumanComment(String documentSha256, String content, String author) {
        assertDocumentExists(documentSha256);
        ArticleCommentEntity comment = new ArticleCommentEntity(
                documentSha256, content, author,
                ArticleCommentEntity.CommentType.HUMAN, null, null);
        return commentRepo.save(comment);
    }

    // ── Génération IA (RAG → LLM) ────────────────────────────────────────────

    /**
     * Génère un commentaire IA en deux étapes :
     * 1. Retrieval RAG : récupère les chunks les plus pertinents du document.
     * 2. Génération : le LLM produit le commentaire en se basant sur ce contexte.
     *
     * @param documentSha256 SHA-256 du document cible
     * @param focus          Angle d'analyse souhaité (ex. "points de sécurité")
     * @param author         Identifiant de l'auteur
     */
    public ArticleCommentEntity generateAiComment(String documentSha256, String focus, String author) {
        var doc = fileRepo.findById(documentSha256)
                .orElseThrow(() -> new NoSuchElementException("Document introuvable : " + documentSha256));

        String focusQuery = (focus != null && !focus.isBlank())
                ? focus
                : "Résume les points clés et les thèmes principaux de ce document.";

        QueryRequest req = new QueryRequest(
                focusQuery,
                6,       // maxContextChunks : plus que pour une Q&A standard
                20,
                doc.getCollectionName(),
                0.4f,    // température basse : commentaire factuel
                0.9f,
                null,
                null
        );

        RagService.RagContext ctx = ragService.retrieveContext(req);

        String ragContextJson = serializeRagContext(ctx.contextChunks());

        String contextBlock = String.join("\n\n---\n\n", ctx.contextChunks());
        String userMessage = """
                Document : %s
                Focus demandé : %s

                === EXTRAITS DU DOCUMENT ===
                %s
                === FIN DES EXTRAITS ===

                Rédige maintenant le commentaire analytique.
                """.formatted(doc.getFileName(), focusQuery, contextBlock);

        String generated = chatClient.chat(COMMENT_SYSTEM_PROMPT, userMessage);
        if (generated == null || generated.isBlank()) {
            throw new IllegalStateException("Le LLM n'a pas retourné de commentaire.");
        }

        ArticleCommentEntity comment = new ArticleCommentEntity(
                documentSha256, generated.trim(), author,
                ArticleCommentEntity.CommentType.AI_GENERATED, ragContextJson, focusQuery);
        return commentRepo.save(comment);
    }

    // ── Évaluation (rating) ──────────────────────────────────────────────────

    /**
     * Note un commentaire IA et déclenche automatiquement un re-entraînement DPO
     * quand le nombre de commentaires approuvés atteint un multiple du seuil configuré.
     */
    public ArticleCommentEntity rateComment(Long commentId, ArticleCommentEntity.Rating rating) {
        ArticleCommentEntity comment = commentRepo.findById(commentId)
                .orElseThrow(() -> new NoSuchElementException("Commentaire introuvable : " + commentId));
        comment.setRating(rating);
        ArticleCommentEntity saved = commentRepo.save(comment);

        if (rating == ArticleCommentEntity.Rating.APPROVED) {
            long approvedCount = commentRepo.countByCommentTypeAndRating(
                    ArticleCommentEntity.CommentType.AI_GENERATED,
                    ArticleCommentEntity.Rating.APPROVED);
            // autoRetrainThreshold <= 0 = auto-réentraînement désactivé (évite aussi la division par zéro).
            if (autoRetrainThreshold > 0 && approvedCount > 0 && approvedCount % autoRetrainThreshold == 0) {
                log.info("Seuil de re-entraînement atteint ({}/{}) — déclenchement automatique DPO",
                        approvedCount, autoRetrainThreshold);
                CompletableFuture.runAsync(() -> triggerRetraining(approvedCount));
            }
        }
        return saved;
    }

    /**
     * Exporte les paires DPO et soumet un job de fine-tuning avec alignement DPO.
     * Exécuté hors du thread HTTP pour ne pas bloquer la réponse.
     */
    private void triggerRetraining(long approvedCount) {
        try {
            int pairs = exportDpoPairs(dpoExportPath);
            if (pairs == 0) {
                log.warn("Re-entraînement automatique annulé : aucune paire DPO exportable");
                return;
            }
            String modelName = "auto-dpo-" + Instant.now().toEpochMilli();
            FineTuningRequest request = new FineTuningRequest(
                    modelName, null, null, null, null, null, null, null, true, null);
            String jobId = fineTuningService.submit(request);
            log.info("Re-entraînement DPO automatique soumis : {} paires, {} approuvés, jobId={}",
                    pairs, approvedCount, jobId);
        } catch (Exception e) {
            log.warn("Échec du re-entraînement automatique : {}", e.getMessage());
        }
    }

    public int getAutoRetrainThreshold() {
        return autoRetrainThreshold;
    }

    // ── Suppression ──────────────────────────────────────────────────────────

    public void deleteComment(Long commentId) {
        if (!commentRepo.existsById(commentId)) {
            throw new NoSuchElementException("Commentaire introuvable : " + commentId);
        }
        commentRepo.deleteById(commentId);
    }

    // ── Export DPO pour fine-tuning ──────────────────────────────────────────

    /**
     * Exporte les commentaires IA évalués sous forme de paires DPO.
     *
     * <p>Pour chaque prompt (focus) ayant au moins un APPROVED et un REJECTED,
     * on émet {@code {"prompt","chosen","rejected","source","exportedAt"}}.
     *
     * <p>Si un prompt n'a que des APPROVED, on génère un rejected synthétique
     * via le LLM (hallucination plausible) pour constituer la paire.
     * Les paires dont chosen et rejected sont trop similaires (Jaccard > 0.85) sont exclues.
     *
     * @param outputPath chemin du fichier JSONL de sortie
     * @return nombre de paires exportées
     */
    public int exportDpoPairs(Path outputPath) throws Exception {
        List<ArticleCommentEntity> approved = commentRepo.findByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.APPROVED);

        if (approved.isEmpty()) {
            log.info("Export DPO : aucun commentaire approuvé — rien à exporter.");
            return 0;
        }

        List<ArticleCommentEntity> rejected = commentRepo.findByCommentTypeAndRating(
                ArticleCommentEntity.CommentType.AI_GENERATED,
                ArticleCommentEntity.Rating.REJECTED);

        // Index rejected by focus for quick lookup
        Map<String, List<String>> rejectedByFocus = new java.util.HashMap<>();
        for (ArticleCommentEntity r : rejected) {
            rejectedByFocus.computeIfAbsent(r.getFocus(), k -> new ArrayList<>()).add(r.getContent());
        }

        Files.createDirectories(outputPath.getParent());
        int count = 0;

        try (var writer = Files.newBufferedWriter(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            for (ArticleCommentEntity app : approved) {
                String focus = app.getFocus();
                List<String> rejects = rejectedByFocus.getOrDefault(focus, List.of());
                String rejectedContent = null;

                for (String candidate : rejects) {
                    if (jaccardSimilarity(app.getContent(), candidate) <= SIMILARITY_THRESHOLD) {
                        rejectedContent = candidate;
                        break;
                    }
                }

                if (rejectedContent == null) {
                    rejectedContent = generateSyntheticRejected(focus, app.getContent());
                    if (rejectedContent == null) continue;
                }

                Map<String, Object> pair = Map.of(
                        "prompt",     focus,
                        "chosen",     app.getContent(),
                        "rejected",   rejectedContent,
                        "source",     "article_comment:" + app.getDocumentSha256(),
                        "exportedAt", Instant.now().toString()
                );
                writer.write(mapper.writeValueAsString(pair));
                writer.newLine();
                count++;
            }
        }
        log.info("Export DPO : {} paires exportées vers {}", count, outputPath);
        return count;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void assertDocumentExists(String sha256) {
        if (!fileRepo.existsById(sha256)) {
            throw new NoSuchElementException("Document introuvable : " + sha256);
        }
    }

    private String serializeRagContext(List<String> chunks) {
        try {
            return mapper.writeValueAsString(chunks);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String generateSyntheticRejected(String focus, String chosen) {
        String sysPrompt = """
                Tu génères des commentaires incorrects pour l'entraînement DPO.
                Produis une version du commentaire qui semble professionnelle mais contient
                des erreurs factuelles subtiles ou des interprétations erronées.
                Ne fournis que le commentaire incorrect, sans préambule.
                """;
        try {
            String result = chatClient.chat(sysPrompt,
                    "Focus : " + focus + "\n\nCommentaire de référence :\n" + chosen);
            if (result == null || result.isBlank()) return null;
            String candidate = result.trim();
            if (jaccardSimilarity(chosen, candidate) > SIMILARITY_THRESHOLD) {
                log.debug("Rejet synthétique trop similaire au chosen (Jaccard > {}) — ignoré", SIMILARITY_THRESHOLD);
                return null;
            }
            return candidate;
        } catch (Exception e) {
            log.warn("Impossible de générer un rejet synthétique : {}", e.getMessage());
            return null;
        }
    }

    /**
     * Similarité de Jaccard sur ensembles de mots (minuscules) pour détecter les paires trop proches.
     */
    private double jaccardSimilarity(String a, String b) {
        Set<String> setA = Arrays.stream(a.toLowerCase().split("\\s+")).collect(Collectors.toSet());
        Set<String> setB = Arrays.stream(b.toLowerCase().split("\\s+")).collect(Collectors.toSet());
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        long intersection = setA.stream().filter(setB::contains).count();
        long union = setA.size() + setB.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }
}
