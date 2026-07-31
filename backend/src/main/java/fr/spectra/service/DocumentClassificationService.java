package fr.spectra.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R8 — Classification automatique des documents par le LLM.
 *
 * <p><b>Pourquoi.</b> Un corpus d'ingestion n'est exploitable pour l'entraînement que si on
 * sait ce qu'il contient. Tant que les documents ne sont identifiés que par leur nom de
 * fichier, impossible de savoir que la moitié du corpus parle de maintenance et que la
 * réglementation n'est représentée que par trois documents — donc impossible de composer un
 * jeu d'entraînement équilibré. La classification attribue à chaque document les catégories
 * qui caractérisent son contenu, ce qui rend le corpus filtrable et mesurable.</p>
 *
 * <p><b>Comment.</b> On ne relit pas le fichier d'origine : ses chunks sont déjà dans
 * ChromaDB. On en échantillonne un sous-ensemble <i>réparti sur tout le document</i>
 * (début, milieu, fin — un document ne se caractérise pas par sa seule page de garde), on
 * présente cet extrait au {@link LlmChatClient} avec un prompt d'étiquetage strict, et on
 * parse sa réponse JSON. Les catégories sont validées contre une taxonomie configurable :
 * le modèle propose, la configuration dispose.</p>
 *
 * <p><b>La boucle se referme.</b> Le modèle utilisé est celui que Spectra vient d'entraîner
 * sur ce même corpus — c'est le sens de la démarche : un modèle spécialisé du domaine
 * étiquette mieux les documents du domaine qu'un classifieur générique. Le nom du modèle est
 * conservé dans {@code classifierModel}, de sorte qu'une reclassification après un nouveau
 * fine-tuning reste traçable et comparable.</p>
 */
@Service
public class DocumentClassificationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentClassificationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Longueur maximale d'une étiquette libre (taxonomie ouverte) — garde-fou anti-phrase. */
    private static final int MAX_LABEL_LENGTH = 48;

    /** Purge des tâches de lot terminées après une heure (aligné sur le générateur de dataset). */
    private static final long TASK_TTL_SECONDS = 3600;

    private final IngestedFileRepository fileRepo;
    private final GedService gedService;
    private final ChromaDbClient chromaDbClient;
    private final LlmChatClient chatClient;
    private final SpectraProperties.ClassificationProperties config;
    private final String defaultCollection;

    /** Taxonomie indexée par forme normalisée → étiquette canonique. */
    private final Map<String, String> canonicalByNormalized;

    private final Map<String, BatchTask> batchTasks = new ConcurrentHashMap<>();
    private final Set<String> cancelledBatches = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean batchRunning = new AtomicBoolean(false);
    /** Dernière valeur de bornage journalisée, pour ne pas répéter l'avertissement par document. */
    private final java.util.concurrent.atomic.AtomicInteger lastClampLogged =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** Auto-référence pour franchir le proxy Spring lors de l'appel {@code @Async}. */
    @Lazy @Autowired(required = false)
    private DocumentClassificationService self;

    public DocumentClassificationService(IngestedFileRepository fileRepo,
                                         GedService gedService,
                                         ChromaDbClient chromaDbClient,
                                         LlmChatClient chatClient,
                                         SpectraProperties properties) {
        this.fileRepo = fileRepo;
        this.gedService = gedService;
        this.chromaDbClient = chromaDbClient;
        this.chatClient = chatClient;
        this.config = properties.classification() != null
                ? properties.classification()
                : SpectraProperties.ClassificationProperties.defaults();
        this.defaultCollection = properties.chromadb() != null
                ? properties.chromadb().effectiveCollection()
                : "spectra_documents";

        Map<String, String> index = new LinkedHashMap<>();
        for (String label : config.effectiveTaxonomy()) {
            index.put(normalize(label), label);
        }
        this.canonicalByNormalized = Map.copyOf(index);
    }

    // ── API ───────────────────────────────────────────────────────────────────

    public boolean isEnabled()      { return config.isEnabled(); }
    public boolean isAutoClassify() { return config.isEnabled() && config.isAutoClassify(); }

    /** Taxonomie effective, dans l'ordre de configuration. */
    public List<String> getTaxonomy() { return config.effectiveTaxonomy(); }

    /** Paramètres exposés à l'UI pour expliquer le comportement du classifieur. */
    public Map<String, Object> describeConfiguration() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",       config.isEnabled());
        m.put("autoClassify",  config.isAutoClassify());
        m.put("openTaxonomy",  config.isOpenTaxonomy());
        m.put("taxonomy",      getTaxonomy());
        m.put("maxCategories", config.effectiveMaxCategories());
        m.put("minConfidence", config.effectiveMinConfidence());
        m.put("model",         activeModel());
        return m;
    }

    /** Résultat d'une classification, tel que renvoyé par l'API. */
    public record ClassificationResult(
            String sha256,
            String fileName,
            List<String> categories,
            Map<String, Double> scores,
            String summary,
            String model,
            Instant classifiedAt,
            /** true quand la classification existante a été réutilisée (appel sans {@code force}). */
            boolean reused
    ) {}

    /**
     * Classifie un document et persiste le résultat.
     *
     * @param force recalcule même si le document porte déjà une classification
     * @throws NoSuchElementException  document inconnu de la GED
     * @throws IllegalStateException   service désactivé, document sans chunks indexés,
     *                                 ou réponse du modèle inexploitable
     */
    public ClassificationResult classify(String sha256, String actor, boolean force) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("La classification automatique est désactivée "
                    + "(spectra.classification.enabled=false).");
        }
        IngestedFileEntity doc = fileRepo.findById(sha256)
                .orElseThrow(() -> new NoSuchElementException("Document introuvable : " + sha256));

        if (!force && doc.isClassified()) {
            return new ClassificationResult(sha256, doc.getFileName(), doc.getCategories(),
                    parseScores(doc.getCategoryScores()), doc.getClassificationSummary(),
                    doc.getClassifierModel(), doc.getClassifiedAt(), true);
        }

        String excerpt = buildExcerpt(doc);
        if (excerpt.isBlank()) {
            throw new IllegalStateException(
                    "Aucun contenu indexé pour " + sha256 + " — impossible de classifier "
                    + "(le document a-t-il bien été ingéré dans une collection existante ?)");
        }

        // Décodage contraint : le modèle ne peut émettre qu'un objet JSON valide. Le parsing
        // défensif ci-dessous reste néanmoins en place — un provider sans cette capacité
        // retombe sur une génération libre, et le contenu du JSON n'est pas garanti conforme
        // à notre schéma pour autant.
        String raw = chatClient.chatJson(
                buildSystemPrompt(),
                buildUserMessage(doc, excerpt),
                config.effectiveTemperature(),
                0.9f);

        LlmVerdict verdict = parseVerdict(raw);
        List<ScoredCategory> retained = retain(verdict.categories());
        if (retained.isEmpty()) {
            throw new IllegalStateException(
                    "Le modèle n'a retourné aucune catégorie exploitable pour " + doc.getFileName()
                    + " (réponse : " + preview(raw) + ")");
        }

        List<String> labels = retained.stream().map(ScoredCategory::label).toList();
        Map<String, Double> scores = new LinkedHashMap<>();
        retained.forEach(c -> scores.put(c.label(), c.confidence()));

        String model = activeModel();
        gedService.applyClassification(sha256, labels, serialize(scores),
                verdict.summary(), model, actor);

        log.info("Document {} classifié : {} (modèle {})", doc.getFileName(), labels, model);
        return new ClassificationResult(sha256, doc.getFileName(), labels, scores,
                verdict.summary(), model, Instant.now(), false);
    }

    /**
     * Classification déclenchée juste après une ingestion : asynchrone et best-effort.
     * Une ingestion réussie ne doit jamais être mise en échec parce que le LLM est
     * indisponible ou qu'il a répondu hors format.
     */
    @Async
    public void classifyAfterIngestion(String sha256, boolean force) {
        if (!isAutoClassify()) return;
        try {
            classify(sha256, "auto-classify", force);
        } catch (Exception e) {
            log.warn("Classification automatique échouée pour {} : {}", sha256, e.getMessage());
        }
    }

    /**
     * Point d'entrée du hook d'ingestion : traverse le proxy Spring pour rester asynchrone.
     *
     * @param force à positionner lors d'une ré-ingestion — le contenu a changé, l'ancienne
     *              classification ne décrit plus le document.
     */
    public void triggerAutoClassification(String sha256, boolean force) {
        if (!isAutoClassify()) return;
        (self != null ? self : this).classifyAfterIngestion(sha256, force);
    }

    // ── Classification par lot ────────────────────────────────────────────────

    /** État d'un traitement par lot, interrogeable pendant son exécution. */
    public record BatchTask(String taskId, Status status, int processed, int total,
                            int succeeded, int failed, List<String> errors,
                            Instant createdAt) {
        public enum Status { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED }
    }

    /**
     * Lance une classification par lot en arrière-plan.
     *
     * @param sha256List documents ciblés ; {@code null} ou vide = tous les documents non classifiés
     * @param force      reclassifie les documents déjà classifiés
     * @return identifiant de tâche, ou {@code null} si un lot est déjà en cours (l'appelant
     *         répond 409 — deux lots concurrents saturent le LLM et se disputent les mêmes lignes)
     */
    public String submitBatch(List<String> sha256List, String actor, boolean force) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("La classification automatique est désactivée "
                    + "(spectra.classification.enabled=false).");
        }
        if (!batchRunning.compareAndSet(false, true)) {
            return null;
        }
        String taskId = UUID.randomUUID().toString();
        batchTasks.put(taskId, new BatchTask(taskId, BatchTask.Status.PENDING,
                0, 0, 0, 0, List.of(), Instant.now()));
        try {
            (self != null ? self : this).runBatch(taskId, sha256List, actor, force);
        } catch (RuntimeException e) {
            // La soumission elle-même a échoué : libérer le verrou, sinon plus aucun lot
            // ne peut être lancé jusqu'au redémarrage.
            batchRunning.set(false);
            batchTasks.put(taskId, new BatchTask(taskId, BatchTask.Status.FAILED,
                    0, 0, 0, 0, List.of(e.getMessage()), Instant.now()));
            throw e;
        }
        return taskId;
    }

    @Async
    public void runBatch(String taskId, List<String> sha256List, String actor, boolean force) {
        List<String> targets;
        List<String> errors = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;
        try {
            targets = resolveTargets(sha256List);
            batchTasks.put(taskId, new BatchTask(taskId, BatchTask.Status.PROCESSING,
                    0, targets.size(), 0, 0, List.of(), Instant.now()));

            for (int i = 0; i < targets.size(); i++) {
                if (cancelledBatches.remove(taskId)) {
                    batchTasks.put(taskId, new BatchTask(taskId, BatchTask.Status.CANCELLED,
                            i, targets.size(), succeeded, failed, List.copyOf(errors), Instant.now()));
                    log.info("Classification par lot {} annulée après {} documents", taskId, i);
                    return;
                }
                String sha = targets.get(i);
                try {
                    classify(sha, actor, force);
                    succeeded++;
                } catch (Exception e) {
                    failed++;
                    // Les erreurs sont bornées : un lot de 10 000 documents en échec ne doit
                    // pas conserver 10 000 messages en mémoire.
                    if (errors.size() < 50) errors.add(sha + " : " + e.getMessage());
                }
                batchTasks.put(taskId, new BatchTask(taskId, BatchTask.Status.PROCESSING,
                        i + 1, targets.size(), succeeded, failed, List.copyOf(errors), Instant.now()));
            }

            batchTasks.put(taskId, new BatchTask(taskId, BatchTask.Status.COMPLETED,
                    targets.size(), targets.size(), succeeded, failed, List.copyOf(errors), Instant.now()));
            log.info("Classification par lot {} terminée : {} réussis, {} échoués",
                    taskId, succeeded, failed);
        } catch (Exception e) {
            log.error("Classification par lot {} interrompue : {}", taskId, e.getMessage());
            errors.add(e.getMessage());
            batchTasks.put(taskId, new BatchTask(taskId, BatchTask.Status.FAILED,
                    0, 0, succeeded, failed, List.copyOf(errors), Instant.now()));
        } finally {
            cancelledBatches.remove(taskId);
            batchRunning.set(false);
        }
    }

    public BatchTask getBatchTask(String taskId) { return batchTasks.get(taskId); }

    public List<BatchTask> getBatchTasks() { return new ArrayList<>(batchTasks.values()); }

    /** Demande l'arrêt d'un lot en cours ; le thread s'arrête au prochain document. */
    public boolean cancelBatch(String taskId) {
        BatchTask task = batchTasks.get(taskId);
        if (task == null) return false;
        if (task.status() == BatchTask.Status.COMPLETED
                || task.status() == BatchTask.Status.FAILED
                || task.status() == BatchTask.Status.CANCELLED) return false;
        cancelledBatches.add(taskId);
        return true;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupOldBatchTasks() {
        Instant cutoff = Instant.now().minusSeconds(TASK_TTL_SECONDS);
        batchTasks.entrySet().removeIf(e -> {
            BatchTask t = e.getValue();
            return (t.status() == BatchTask.Status.COMPLETED
                    || t.status() == BatchTask.Status.FAILED
                    || t.status() == BatchTask.Status.CANCELLED)
                    && t.createdAt() != null && t.createdAt().isBefore(cutoff);
        });
    }

    /** Documents à traiter : la liste explicite, ou toute la file des non-classifiés. */
    private List<String> resolveTargets(List<String> sha256List) {
        if (sha256List != null && !sha256List.isEmpty()) {
            return sha256List.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        }
        List<String> all = new ArrayList<>();
        int page = 0;
        while (true) {
            var slice = fileRepo.findUnclassified(PageRequest.of(page, 500));
            slice.getContent().forEach(d -> all.add(d.getSha256()));
            if (!slice.hasNext()) break;
            page++;
        }
        return all;
    }

    // ── Construction de l'extrait ─────────────────────────────────────────────

    /**
     * Assemble un extrait représentatif du document à partir de ses chunks indexés.
     *
     * <p>L'échantillonnage est <b>réparti sur toute la longueur</b> plutôt que pris en tête :
     * les premiers chunks d'un document sont souvent une page de garde, un sommaire ou un
     * en-tête administratif, qui caractérisent mal le fond. Les chunks retenus sont ensuite
     * tronqués à un budget de caractères pour tenir dans la fenêtre de contexte.</p>
     */
    String buildExcerpt(IngestedFileEntity doc) {
        String collection = doc.getCollectionName() != null && !doc.getCollectionName().isBlank()
                ? doc.getCollectionName()
                : defaultCollection;

        List<String> chunks;
        try {
            String collectionId = chromaDbClient.resolveCollectionIdUnchecked(collection);
            // Plafond de lecture large : on échantillonne ensuite localement pour couvrir
            // tout le document, ce qu'un simple `limit = maxChunks` côté ChromaDB ne ferait
            // pas (il rendrait toujours les mêmes premiers chunks).
            chunks = chromaDbClient.getDocumentTextsByMetadata(
                    collectionId, "sha256", doc.getSha256(), 1000);
            if (chunks.isEmpty() && doc.getFileName() != null) {
                // Repli : chunks historiques indexés avant l'ajout de la métadonnée sha256.
                chunks = chromaDbClient.getDocumentTextsByMetadata(
                        collectionId, "sourceFile", doc.getFileName(), 1000);
            }
        } catch (Exception e) {
            log.warn("Lecture des chunks impossible pour {} : {}", doc.getSha256(), e.getMessage());
            return "";
        }
        if (chunks.isEmpty()) return "";

        return joinSampled(chunks, config.effectiveMaxChunks(), effectiveExcerptChars());
    }

    /**
     * Budget d'extrait réellement applicable : le minimum entre la valeur configurée et ce que
     * la fenêtre servie peut porter.
     *
     * <p><b>Pourquoi borner plutôt qu'obéir.</b> Dépasser la fenêtre ne produit pas d'erreur :
     * llama.cpp tronque le <b>début</b> de la requête — le prompt système, donc les consignes
     * de format et la taxonomie — et le modèle répond en prose. La classification échoue alors
     * sur chaque document, sans que rien n'en désigne la cause. Envoyer moins de texte dégrade
     * la qualité ; en envoyer trop détruit la fonction.</p>
     *
     * <p>Le réglage {@code max-excerpt-chars} devient ainsi une <b>borne haute</b> et non une
     * promesse : sur une machine de 8 à 16 Go, où une requête ne dispose que de 2048 tokens,
     * le défaut de 6000 caractères ne tient pas. Plutôt que d'exiger de l'utilisateur qu'il
     * accorde sa configuration à un dimensionnement qu'il ne voit pas, on s'y ajuste.</p>
     *
     * <p>Sans information du serveur, on applique la valeur configurée : mieux vaut le
     * comportement demandé qu'une restriction fondée sur une fenêtre supposée.</p>
     */
    int effectiveExcerptChars() {
        int configured = config.effectiveMaxExcerptChars();
        OptionalInt window = chatClient.servedContextTokens();
        if (window.isEmpty()) return configured;

        int affordable = ContextBudgetValidator.affordableExcerptChars(window.getAsInt());
        if (affordable >= configured) return configured;

        // Journalisé une fois par valeur : un lot de mille documents ne doit pas produire
        // mille lignes identiques, mais un changement de fenêtre doit rester visible.
        if (lastClampLogged.getAndSet(affordable) != affordable) {
            log.warn("Extrait de classification ramené de {} à {} caractères : la fenêtre servie "
                            + "est de {} tokens par requête. Augmentez LLM_CONTEXT (divisé par "
                            + "LLM_PARALLEL) pour exploiter la valeur configurée.",
                    configured, affordable, window.getAsInt());
        }
        return affordable;
    }

    /** Échantillonne {@code maxChunks} extraits régulièrement espacés, sous un budget de caractères. */
    static String joinSampled(List<String> chunks, int maxChunks, int maxChars) {
        List<String> sampled;
        if (chunks.size() <= maxChunks) {
            sampled = chunks;
        } else {
            sampled = new ArrayList<>(maxChunks);
            // Pas fractionnaire : couvre le document de bout en bout, y compris le dernier chunk.
            double step = (double) (chunks.size() - 1) / (maxChunks - 1);
            for (int i = 0; i < maxChunks; i++) {
                sampled.add(chunks.get((int) Math.round(i * step)));
            }
        }

        StringBuilder sb = new StringBuilder();
        // Budget réparti équitablement : un premier chunk très long ne doit pas consommer
        // tout l'extrait et faire disparaître la fin du document.
        int perChunk = Math.max(200, maxChars / Math.max(1, sampled.size()));
        for (String chunk : sampled) {
            if (sb.length() >= maxChars) break;
            String text = chunk.strip();
            if (text.isEmpty()) continue;
            if (text.length() > perChunk) text = text.substring(0, perChunk) + "…";
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(text);
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) : sb.toString();
    }

    // ── Prompt ────────────────────────────────────────────────────────────────

    /**
     * Prompt d'étiquetage. Il est volontairement rigide (JSON seul, catégories imposées,
     * confiance obligatoire) : les modèles locaux de petite taille commentent leur réponse
     * dès qu'on leur en laisse la latitude, et un préambule en prose casse le parsing.
     */
    String buildSystemPrompt() {
        String taxonomyBlock = String.join("\n", getTaxonomy().stream().map(c -> "- " + c).toList());
        String rule = config.isOpenTaxonomy()
                ? """
                  2. Privilégie les catégories de la liste ci-dessous. Si et seulement si aucune
                     ne convient, tu peux proposer une catégorie de ton choix, en un ou deux mots.
                  """
                : """
                  2. N'utilise QUE des catégories de la liste ci-dessous, orthographiées à
                     l'identique. Toute catégorie hors liste est ignorée.
                  """;

        return """
               Tu es un classifieur documentaire. Tu analyses des extraits d'un document et tu
               lui attribues les catégories qui caractérisent son contenu.

               RÈGLES
               1. Attribue au maximum %d catégories, de la plus pertinente à la moins pertinente.
               %s3. Associe à chaque catégorie une confiance entre 0.0 et 1.0.
               4. Fonde-toi uniquement sur les extraits fournis ; n'invente aucune information.
               5. Rédige un résumé factuel d'une seule phrase (25 mots maximum).
               6. Réponds EXCLUSIVEMENT par un objet JSON valide : aucun texte avant ou après,
                  aucun bloc de code, aucun commentaire.

               CATÉGORIES AUTORISÉES
               %s

               FORMAT DE RÉPONSE ATTENDU
               {"categories":[{"label":"procedures","confidence":0.92},{"label":"securite","confidence":0.68}],"summary":"Procédure d'intervention sur les postes haute tension."}
               """.formatted(config.effectiveMaxCategories(), rule, taxonomyBlock);
    }

    String buildUserMessage(IngestedFileEntity doc, String excerpt) {
        return """
               Document : %s
               Format : %s

               === EXTRAITS DU DOCUMENT ===
               %s
               === FIN DES EXTRAITS ===

               Classe ce document. Réponds uniquement par le JSON demandé.
               """.formatted(
                doc.getFileName() != null ? doc.getFileName() : "(sans nom)",
                doc.getFormat() != null ? doc.getFormat() : "INCONNU",
                excerpt);
    }

    // ── Parsing et validation de la réponse ──────────────────────────────────

    record ScoredCategory(String label, double confidence) {}

    record LlmVerdict(List<ScoredCategory> categories, String summary) {}

    /**
     * Extrait le verdict de la réponse brute du modèle.
     *
     * <p>Tolérant par construction : les modèles locaux encadrent volontiers leur JSON d'un
     * bloc <code>```json</code> ou d'une phrase d'introduction, et rendent parfois
     * {@code "categories": ["a","b"]} au lieu d'objets scorés. Ces trois formes sont acceptées ;
     * tout le reste lève une {@link IllegalStateException} explicite.</p>
     */
    static LlmVerdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Le modèle n'a retourné aucune réponse.");
        }
        // Le modèle de chat par défaut est un modèle « reasoning » : il expose sa réflexion
        // avant de répondre. Ce préambule contient souvent des accolades (extraits, exemples),
        // dont un premier objet JSON parfaitement équilibré mais qui n'est pas la réponse.
        String cleaned = stripReasoning(raw);

        List<String> candidates = jsonObjectCandidates(cleaned);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("Réponse du modèle sans objet JSON : " + preview(raw));
        }

        JsonNode root = null;
        JsonNode fallback = null;
        for (String candidate : candidates) {
            JsonNode parsed;
            try {
                parsed = MAPPER.readTree(candidate);
            } catch (Exception ignored) {
                continue;   // fragment non parsable : on tente le suivant
            }
            if (parsed.has("categories")) { root = parsed; break; }
            if (fallback == null) fallback = parsed;
        }
        if (root == null) root = fallback;

        // Des accolades équilibrées existaient, mais rien n'était du JSON valide : le
        // distinguer du cas « aucun JSON » aide à savoir si le modèle a essayé le format.
        if (root == null) {
            throw new IllegalStateException("JSON invalide dans la réponse du modèle : " + preview(raw));
        }

        JsonNode categoriesNode = root.path("categories");
        List<ScoredCategory> categories = new ArrayList<>();
        if (categoriesNode.isArray()) {
            for (JsonNode node : categoriesNode) {
                if (node.isTextual()) {
                    // Forme dégradée ["procedures", …] : le modèle a affirmé l'étiquette sans
                    // la nuancer. On ne peut pas filtrer sur une confiance qu'il n'a pas donnée.
                    categories.add(new ScoredCategory(node.asText(), 1.0));
                } else if (node.isObject()) {
                    String label = node.path("label").asText(node.path("category").asText(null));
                    if (label == null || label.isBlank()) continue;
                    JsonNode conf = node.path("confidence");
                    double confidence = conf.isNumber() ? conf.asDouble() : 1.0;
                    categories.add(new ScoredCategory(label, confidence));
                }
            }
        }

        String summary = root.path("summary").asText(null);
        if (summary != null) {
            summary = summary.strip();
            if (summary.isEmpty()) summary = null;
        }
        return new LlmVerdict(categories, summary);
    }

    /** Blocs de réflexion des modèles « reasoning », à écarter avant de chercher le JSON. */
    private static final java.util.regex.Pattern REASONING_BLOCK = java.util.regex.Pattern.compile(
            "<\\s*(think|thinking|reasoning|scratchpad)\\s*>.*?<\\s*/\\s*\\1\\s*>",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /** Bloc de réflexion jamais refermé (génération coupée par la limite de tokens). */
    private static final java.util.regex.Pattern UNCLOSED_REASONING = java.util.regex.Pattern.compile(
            "<\\s*(think|thinking|reasoning|scratchpad)\\s*>.*",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /**
     * Retire la réflexion d'un modèle « reasoning » (le défaut du déploiement en est un).
     *
     * <p>Un bloc non refermé est supprimé jusqu'à la fin : il signifie que la génération a été
     * coupée avant la réponse, et ce qu'il contient n'est pas un verdict.</p>
     */
    static String stripReasoning(String raw) {
        String withoutBlocks = REASONING_BLOCK.matcher(raw).replaceAll(" ");
        return UNCLOSED_REASONING.matcher(withoutBlocks).replaceAll(" ").strip();
    }

    /**
     * Isole le premier objet JSON équilibré de la réponse — un simple
     * {@code indexOf('{')}/{@code lastIndexOf('}')} avalait la prose qui suit parfois le JSON.
     */
    static String extractJsonObject(String raw) {
        List<String> candidates = jsonObjectCandidates(raw);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Énumère tous les objets JSON équilibrés de premier niveau présents dans le texte.
     *
     * <p>Nécessaire parce que le premier objet rencontré n'est pas toujours la réponse : un
     * modèle bavard cite volontiers le format attendu, ou un extrait du document, avant de
     * conclure. L'appelant choisit celui qui porte réellement la clé {@code categories}.</p>
     */
    static List<String> jsonObjectCandidates(String raw) {
        List<String> candidates = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped)        escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"')  inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}' && depth > 0 && --depth == 0 && start >= 0) {
                candidates.add(raw.substring(start, i + 1));
                start = -1;
            }
        }
        return candidates;
    }

    /**
     * Applique la politique de taxonomie : canonisation, filtrage par confiance,
     * déduplication et plafonnement.
     */
    List<ScoredCategory> retain(List<ScoredCategory> proposed) {
        double minConfidence = config.effectiveMinConfidence();
        Map<String, Double> best = new LinkedHashMap<>();

        for (ScoredCategory candidate : proposed) {
            String canonical = canonicalize(candidate.label());
            if (canonical == null) {
                log.debug("Catégorie « {} » hors taxonomie — ignorée", candidate.label());
                continue;
            }
            double confidence = Math.max(0.0, Math.min(1.0, candidate.confidence()));
            if (confidence < minConfidence) continue;
            // Un modèle peut proposer deux variantes de la même étiquette ; on garde la
            // meilleure confiance plutôt que la dernière rencontrée.
            best.merge(canonical, confidence, Math::max);
        }

        return best.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(config.effectiveMaxCategories())
                .map(e -> new ScoredCategory(e.getKey(), round(e.getValue())))
                .toList();
    }

    /**
     * Ramène une étiquette proposée à sa forme canonique, ou {@code null} si elle doit être
     * rejetée. La comparaison ignore la casse, les accents et la ponctuation : un modèle qui
     * répond « Procédures » désigne bien la catégorie {@code procedures}.
     */
    String canonicalize(String label) {
        if (label == null) return null;
        String normalized = normalize(label);
        if (normalized.isEmpty()) return null;

        String canonical = canonicalByNormalized.get(normalized);
        if (canonical != null) return canonical;
        if (!config.isOpenTaxonomy()) return null;
        if (normalized.length() > MAX_LABEL_LENGTH) return null;
        return normalized;
    }

    /** Minuscules, sans accents, ponctuation réduite, espaces normalisés en tirets. */
    static String normalize(String value) {
        String stripped = Normalizer.normalize(value.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        return stripped.replaceAll("[^a-z0-9]+", "-")
                       .replaceAll("^-+|-+$", "");
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String preview(String raw) {
        String flat = raw.strip().replaceAll("\\s+", " ");
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    private String activeModel() {
        try {
            String model = chatClient.getActiveModel();
            return model != null && !model.isBlank() ? model : "inconnu";
        } catch (Exception e) {
            return "inconnu";
        }
    }

    private static String serialize(Map<String, Double> scores) {
        try {
            return MAPPER.writeValueAsString(scores);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Relit la colonne {@code category_scores} ; map vide si absente ou corrompue. */
    public static Map<String, Double> parseScores(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
