package fr.spectra.service.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.spectra.config.SpectraProperties;
import fr.spectra.model.TrainingPair;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import fr.spectra.service.ChromaDbClient;
import fr.spectra.service.LlmChatClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Génération de dataset synthétique — le carburant du fine-tuning.
 *
 * <p><b>Pourquoi générer un dataset ?</b> Pour spécialiser un modèle par fine-tuning, il faut
 * des milliers d'exemples question/réponse de votre domaine. Les annoter à la main est hors de
 * portée. L'astuce de Spectra : <i>réutiliser vos propres documents</i> déjà ingérés. On
 * présente chaque chunk au LLM en lui demandant de fabriquer des paires Q/R ancrées dans ce
 * texte — c'est l'approche « auto-instruct » / distillation : le modèle génère les données qui
 * serviront ensuite à l'entraîner. Cela ferme la boucle <b>RAG → dataset → fine-tuning</b>.</p>
 *
 * <p><b>Comment.</b> Pour chaque chunk tiré de ChromaDB ({@link ChromaDbClient}), le
 * {@link LlmChatClient} produit des {@link TrainingPair} (question, réponse, catégorie, type,
 * score de confiance). Les paires sont accumulées en mémoire et <b>persistées au fil de l'eau</b>
 * en JSONL ({@code sft_pairs.jsonl}) — donc rechargées au redémarrage ({@code @PostConstruct}),
 * pour ne jamais reperdre une génération longue.</p>
 *
 * <p><b>Exécution.</b> La génération est asynchrone ({@code @Async}) avec suivi de progression
 * par tâche, annulation ({@code cancelledTasks}) et un verrou {@code generationRunning} qui
 * empêche deux générations simultanées. Le champ {@code self} (auto-référence {@code @Lazy})
 * permet d'invoquer la méthode {@code @Async} à travers le proxy Spring sans dépendance
 * circulaire.</p>
 */
@Service
public class DatasetGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(DatasetGeneratorService.class);
    private static final String COLLECTION_NAME = "spectra_documents";
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Bucket des chunks issus d'un document sans classification (R8). */
    static final String UNCLASSIFIED_BUCKET = "(non classé)";

    /**
     * Réponses de refus pour les exemples « négatifs » : on apprend au modèle à
     * <b>s'abstenir</b> quand l'information n'est pas dans les documents, plutôt que d'halluciner.
     * Plusieurs formulations pour éviter la mémorisation d'une chaîne unique.
     */
    private static final List<String> REFUSAL_ANSWERS = List.of(
            "Je ne dispose pas de cette information dans les documents fournis.",
            "Cette information n'apparaît pas dans la documentation disponible ; je préfère ne pas répondre plutôt que de risquer une erreur.",
            "Les documents d'exploitation à ma disposition ne couvrent pas ce point, je ne peux donc pas répondre avec certitude.",
            "Je n'ai pas d'élément fiable sur ce sujet dans le corpus fourni.");

    private final LlmChatClient llmChatClient;
    private final ChromaDbClient chromaDbClient;
    private final IngestedFileRepository fileRepo;
    private final Path pairsFile;
    /** Fréquence des exemples de refus : un toutes les N portions (0 = désactivé). */
    private final int refusalEveryN;
    /** Taxonomie de classification (R8) — partagée avec le classifieur documentaire. */
    private final List<String> taxonomy;

    /** Self-reference for @Async proxy — injected lazily to avoid circular dependency. */
    @Lazy @Autowired(required = false)
    private DatasetGeneratorService self;

    private final List<TrainingPair> generatedPairs = new CopyOnWriteArrayList<>();
    private final Map<String, GenerationTask> tasks = new ConcurrentHashMap<>();
    private final AtomicBoolean generationRunning = new AtomicBoolean(false);
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();

    public DatasetGeneratorService(LlmChatClient llmChatClient,
                                   ChromaDbClient chromaDbClient,
                                   IngestedFileRepository fileRepo,
                                   SpectraProperties properties,
                                   @Value("${spectra.dataset.dir:./data/dataset}") String datasetDir,
                                   @Value("${spectra.dataset.refusal-every-n:3}") int refusalEveryN) {
        this.llmChatClient = llmChatClient;
        this.chromaDbClient = chromaDbClient;
        this.fileRepo = fileRepo;
        this.pairsFile = Path.of(datasetDir).resolve("sft_pairs.jsonl");
        this.refusalEveryN = refusalEveryN;
        this.taxonomy = properties != null && properties.classification() != null
                ? properties.classification().effectiveTaxonomy()
                : SpectraProperties.ClassificationProperties.DEFAULT_TAXONOMY;
    }

    @PostConstruct
    private void loadPersistedPairs() {
        if (!Files.exists(pairsFile)) return;
        try (var lines = Files.lines(pairsFile)) {
            lines.forEach(line -> {
                try { generatedPairs.add(mapper.readValue(line, TrainingPair.class)); }
                catch (Exception ignored) {}
            });
            log.info("Paires SFT restaurées depuis {}: {}", pairsFile.getFileName(), generatedPairs.size());
        } catch (Exception e) {
            log.warn("Impossible de charger {}: {}", pairsFile, e.getMessage());
        }
    }

    public record GenerationTask(String taskId, Status status, int pairsGenerated, int chunksProcessed,
                                  int totalChunks, String error, Instant createdAt) {
        public enum Status { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED }
    }

    /**
     * Lance la génération asynchrone. {@code maxChunks=0} signifie "tous les chunks".
     * Retourne {@code null} si une génération est déjà en cours (appelant doit retourner 409).
     */
    public String submit(int maxChunks) {
        if (!generationRunning.compareAndSet(false, true)) {
            return null;
        }
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PENDING, 0, 0, 0, null, Instant.now()));
        DatasetGeneratorService proxy = (self != null) ? self : this;
        proxy.generateAsync(taskId, maxChunks);
        return taskId;
    }

    public GenerationTask getTask(String taskId) {
        return tasks.get(taskId);
    }

    public List<GenerationTask> getAllTasks() {
        return new ArrayList<>(tasks.values());
    }

    public boolean cancelTask(String taskId) {
        GenerationTask task = tasks.get(taskId);
        if (task == null) return false;
        if (task.status() == GenerationTask.Status.COMPLETED
                || task.status() == GenerationTask.Status.FAILED
                || task.status() == GenerationTask.Status.CANCELLED) return false;
        cancelledTasks.add(taskId);
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.CANCELLED,
                task.pairsGenerated(), task.chunksProcessed(), task.totalChunks(),
                "Annulé par l'utilisateur", task.createdAt()));
        // NE PAS libérer generationRunning ici : le thread de génération le fait dans son
        // finally quand il atteint le point d'annulation. Le libérer prématurément permettait
        // de démarrer une 2e génération concurrente pendant que la 1re tournait encore
        // (deux threads mutant generatedPairs et le fichier JSONL simultanément).
        return true;
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void cleanupOldTasks() {
        Instant cutoff = Instant.now().minusSeconds(3600);
        tasks.entrySet().removeIf(e -> {
            GenerationTask t = e.getValue();
            return (t.status() == GenerationTask.Status.COMPLETED
                    || t.status() == GenerationTask.Status.FAILED
                    || t.status() == GenerationTask.Status.CANCELLED)
                    && t.createdAt() != null && t.createdAt().isBefore(cutoff);
        });
    }

    public List<TrainingPair> getAllPairs() {
        return List.copyOf(generatedPairs);
    }

    /**
     * Génération SYNCHRONE (utilisée par le pipeline batch). Contrairement à
     * {@link #submit}, cette méthode bloque jusqu'à la fin de la génération et
     * retourne le nombre réel de paires produites — l'appelant (BatchService) peut
     * donc enchaîner le fine-tuning en toute sécurité. Elle acquiert le même verrou
     * {@code generationRunning} pour ne pas s'exécuter en parallèle d'un {@code submit}.
     *
     * @return le nombre de paires générées, ou -1 si une génération est déjà en cours.
     */
    public int generate(String taskId, int maxChunks) {
        if (!generationRunning.compareAndSet(false, true)) {
            log.warn("Génération {} refusée : une génération est déjà en cours", taskId);
            return -1;
        }
        tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PENDING, 0, 0, 0, null, Instant.now()));
        // runGeneration relâche generationRunning dans son bloc finally.
        runGeneration(taskId, maxChunks);
        GenerationTask result = tasks.get(taskId);
        return result != null ? result.pairsGenerated() : generatedPairs.size();
    }

    @Async
    protected void generateAsync(String taskId, int maxChunks) {
        runGeneration(taskId, maxChunks);
    }

    @SuppressWarnings("unchecked")
    private void runGeneration(String taskId, int maxChunks) {
        // Accumulation LOCALE : le dataset courant (generatedPairs) n'est remplacé qu'en fin
        // de génération réussie. Auparavant, un clear() immédiat détruisait le dataset en
        // mémoire dès le lancement — une génération échouée (ChromaDB indisponible…) ou
        // annulée laissait getAllPairs() vide/partiel alors que le JSONL sur disque était
        // intact, faussant évaluations et exports de fine-tuning jusqu'au redémarrage.
        List<TrainingPair> newPairs = new ArrayList<>();
        try {
            String collectionId = chromaDbClient.getOrCreateCollection(COLLECTION_NAME);

            Map<String, Object> allDocs = chromaDbClient.getAllDocuments(collectionId);
            List<String> documents = allDocs != null ? (List<String>) allDocs.get("documents") : null;
            List<Map<String, String>> metadatas = allDocs != null ? (List<Map<String, String>>) allDocs.get("metadatas") : null;
            List<String> ids = allDocs != null ? (List<String>) allDocs.get("ids") : null;

            if (documents == null || documents.isEmpty()) {
                log.info("Aucun chunk disponible dans ChromaDB pour la génération (taskId={})", taskId);
                tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.COMPLETED, 0, 0, 0, null, Instant.now()));
                return;
            }

            if (metadatas == null || ids == null
                    || metadatas.size() != documents.size()
                    || ids.size() != documents.size()) {
                String msg = String.format("Réponse ChromaDB incohérente: documents=%d, metadatas=%s, ids=%s",
                        documents.size(),
                        metadatas != null ? metadatas.size() : "null",
                        ids != null ? ids.size() : "null");
                log.error("{} (taskId={})", msg, taskId);
                tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.FAILED, 0, 0, 0, msg, Instant.now()));
                return;
            }

            // R8 — catégorie du document d'origine de chaque chunk, résolue via la métadonnée
            // sha256. Sert deux fins : l'échantillonnage stratifié ci-dessous, et l'étiquetage
            // des paires produites (plus besoin de redemander sa catégorie au LLM).
            Map<String, String> categoryBySha = loadDocumentCategories();
            List<String> chunkCategories = metadatas.stream()
                    .map(meta -> categoryOfChunk(meta, categoryBySha))
                    .toList();

            if (maxChunks > 0 && documents.size() > maxChunks) {
                // Échantillonnage stratifié : on prélève des chunks répartis entre les
                // catégories ET entre les documents de chaque catégorie. Le troncage
                // historique (les N premiers chunks) ne voyait que le début de la collection,
                // c'est-à-dire les tout premiers documents ingérés — un « essai rapide » à
                // 20 chunks entraînait donc le modèle sur un ou deux documents.
                List<String> chunkDocuments = metadatas.stream()
                        .map(DatasetGeneratorService::documentKey)
                        .toList();
                List<Integer> keep = selectStratified(chunkCategories, chunkDocuments, maxChunks);
                int available = documents.size();
                documents       = pick(documents, keep);
                metadatas       = pick(metadatas, keep);
                ids             = pick(ids, keep);
                chunkCategories = pick(chunkCategories, keep);
                log.info("Échantillonnage stratifié : {} chunks retenus sur {} disponibles, répartition {}",
                        documents.size(), available, countByCategory(chunkCategories));
            }

            int total = documents.size();
            tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.PROCESSING, 0, 0, total, null, Instant.now()));
            log.info("Génération de dataset: {} chunks à traiter", total);

            int pairsCount = 0;

            for (int i = 0; i < documents.size(); i++) {
                // Point d'annulation
                if (cancelledTasks.contains(taskId)) {
                    log.info("Génération {} annulée à l'itération {}", taskId, i);
                    tasks.put(taskId, new GenerationTask(taskId, GenerationTask.Status.CANCELLED,
                            pairsCount, i, total, "Annulé par l'utilisateur", Instant.now()));
                    return;
                }

                String chunkText = documents.get(i);
                Map<String, String> meta = metadatas.get(i);
                String sourceFile = meta.getOrDefault("sourceFile", "inconnu");
                // null pour un document non classifié : les paires restent produites, elles
                // ne portent simplement aucun rattachement thématique.
                String docCategory = chunkCategories.get(i).equals(UNCLASSIFIED_BUCKET)
                        ? null : chunkCategories.get(i);

                try {
                    List<TrainingPair> pairs = generatePairsFromChunk(chunkText, sourceFile, docCategory);
                    newPairs.addAll(pairs);
                    pairsCount += pairs.size();

                    // Exemple de refus périodique : apprend l'abstention (anti-hallucination).
                    if (refusalEveryN > 0 && i % refusalEveryN == 0) {
                        TrainingPair refusal = generateRefusalPair(chunkText, sourceFile, i / refusalEveryN, docCategory);
                        if (refusal != null) {
                            newPairs.add(refusal);
                            pairsCount++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Erreur sur chunk {}: {}", ids.get(i), e.getMessage());
                }

                final int fp = pairsCount;
                tasks.put(taskId, new GenerationTask(
                        taskId, GenerationTask.Status.PROCESSING, fp, i + 1, total, null, Instant.now()));
            }

            List<TrainingPair> unique = deduplicate(newPairs);
            // Remplacement atomique du dataset courant, uniquement en cas de succès complet.
            generatedPairs.clear();
            generatedPairs.addAll(unique);
            tasks.put(taskId, new GenerationTask(
                    taskId, GenerationTask.Status.COMPLETED, unique.size(), total, total, null, Instant.now()));
            log.info("Génération terminée: {} paires uniques (sur {} générées) depuis {} chunks",
                    unique.size(), pairsCount, total);
            persistPairs();

        } catch (Exception e) {
            if (cancelledTasks.contains(taskId)) {
                log.info("Génération dataset {} interrompue par annulation: {}", taskId, e.getMessage());
                tasks.put(taskId, new GenerationTask(
                        taskId, GenerationTask.Status.CANCELLED, 0, 0, 0, "Annulé par l'utilisateur", Instant.now()));
            } else {
                log.error("Erreur génération dataset {}: {}", taskId, e.getMessage(), e);
                tasks.put(taskId, new GenerationTask(
                        taskId, GenerationTask.Status.FAILED, 0, 0, 0, e.getMessage(), Instant.now()));
            }
        } finally {
            generationRunning.set(false);
            cancelledTasks.remove(taskId);
        }
    }

    // ── R8 — Stratification par catégorie documentaire ───────────────────────

    /**
     * Catégorie principale de chaque document classifié, indexée par SHA-256.
     *
     * <p>Seule la première catégorie est retenue : elle est la mieux notée (le classifieur
     * trie par confiance décroissante), et l'échantillonnage a besoin d'une affectation
     * unique par document pour que les strates ne se recouvrent pas.</p>
     */
    private Map<String, String> loadDocumentCategories() {
        try {
            Map<String, String> byS = new HashMap<>();
            // Charge les lignes GED (une par document, pas une par chunk) : négligeable
            // devant les chunks déjà tous en mémoire à ce stade.
            for (IngestedFileEntity doc : fileRepo.findAll()) {
                List<String> cats = doc.getCategories();
                if (cats != null && !cats.isEmpty()) {
                    byS.put(doc.getSha256(), cats.get(0));
                }
            }
            return byS;
        } catch (Exception e) {
            // Sans classification, on retombe simplement sur une strate unique.
            log.warn("Catégories GED illisibles, échantillonnage non stratifié : {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Identité du document d'où provient un chunk, pour l'entrelacement intra-catégorie.
     * Le SHA-256 est préféré au nom de fichier (deux documents homonymes sont distincts) ;
     * repli sur {@code sourceFile} pour les chunks indexés avant l'ajout de cette métadonnée.
     */
    private static String documentKey(Map<String, String> meta) {
        if (meta == null) return "";
        String sha = meta.get("sha256");
        if (sha != null && !sha.isBlank()) return sha;
        return meta.getOrDefault("sourceFile", "");
    }

    /** Catégorie du document d'où provient un chunk, ou {@link #UNCLASSIFIED_BUCKET}. */
    private static String categoryOfChunk(Map<String, String> meta, Map<String, String> categoryBySha) {
        if (meta == null) return UNCLASSIFIED_BUCKET;
        String sha = meta.get("sha256");
        if (sha == null) return UNCLASSIFIED_BUCKET;
        return categoryBySha.getOrDefault(sha, UNCLASSIFIED_BUCKET);
    }

    /**
     * Sélectionne {@code maxChunks} indices de chunks répartis équitablement entre les
     * catégories, puis entre les documents de chaque catégorie.
     *
     * <p>La stratification est à <b>deux niveaux</b>, parce qu'un seul ne suffit pas : si la
     * catégorie « maintenance » compte cinquante documents dont le premier pèse deux cents
     * chunks, un tourniquet limité aux catégories puiserait tout son quota dans ce seul
     * document. Les chunks d'une catégorie sont donc d'abord entrelacés entre ses documents.</p>
     *
     * <p>Une catégorie (ou un document) qui s'épuise laisse sa place aux autres : le quota
     * n'est jamais perdu, on rend toujours exactement {@code maxChunks} chunks tant qu'il y
     * en a assez. Les indices sont rendus triés, ce qui préserve l'ordre d'origine de la
     * collection et garde la génération déterministe.</p>
     *
     * @param chunkCategories catégorie du document source de chaque chunk
     * @param chunkDocuments  identité du document source de chaque chunk (même longueur)
     */
    static List<Integer> selectStratified(List<String> chunkCategories,
                                          List<String> chunkDocuments, int maxChunks) {
        // Catégorie → document → indices de ses chunks, dans l'ordre de la collection.
        Map<String, Map<String, List<Integer>>> byCategoryThenDoc = new LinkedHashMap<>();
        for (int i = 0; i < chunkCategories.size(); i++) {
            byCategoryThenDoc
                    .computeIfAbsent(chunkCategories.get(i), k -> new LinkedHashMap<>())
                    .computeIfAbsent(chunkDocuments.get(i), k -> new ArrayList<>())
                    .add(i);
        }

        // Niveau 2 : chaque catégorie devient une file entrelacée entre ses documents.
        List<java.util.Iterator<Integer>> cursors = byCategoryThenDoc.values().stream()
                .map(DatasetGeneratorService::interleave)
                .map(List::iterator)
                .collect(Collectors.toCollection(ArrayList::new));

        // Niveau 1 : tourniquet entre les catégories.
        List<Integer> selected = new ArrayList<>(Math.min(maxChunks, chunkCategories.size()));
        while (selected.size() < maxChunks && !cursors.isEmpty()) {
            cursors.removeIf(cursor -> !cursor.hasNext());
            for (java.util.Iterator<Integer> cursor : cursors) {
                if (selected.size() >= maxChunks) break;
                if (cursor.hasNext()) selected.add(cursor.next());
            }
        }
        Collections.sort(selected);
        return selected;
    }

    /**
     * Entrelace plusieurs suites d'indices en tourniquet : on prend le 1<sup>er</sup> chunk de
     * chaque document, puis le 2<sup>e</sup> de chacun, etc. Les documents plus courts
     * s'épuisent sans bloquer les autres.
     */
    private static List<Integer> interleave(Map<String, List<Integer>> byDocument) {
        List<Integer> flat = new ArrayList<>();
        int longest = byDocument.values().stream().mapToInt(List::size).max().orElse(0);
        for (int position = 0; position < longest; position++) {
            for (List<Integer> indices : byDocument.values()) {
                if (position < indices.size()) flat.add(indices.get(position));
            }
        }
        return flat;
    }

    /** Sous-liste aux indices donnés (les indices sont supposés valides et triés). */
    private static <T> List<T> pick(List<T> source, List<Integer> indices) {
        List<T> out = new ArrayList<>(indices.size());
        indices.forEach(i -> out.add(source.get(i)));
        return out;
    }

    private static Map<String, Long> countByCategory(List<String> categories) {
        return categories.stream().collect(Collectors.groupingBy(c -> c, TreeMap::new, Collectors.counting()));
    }

    /**
     * Génère 3 types de paires pour un chunk : question/réponse, résumé, classification.
     *
     * @param documentCategory catégorie du document source (R8), {@code null} s'il n'est pas
     *                         classifié. Quand elle est connue, la paire de classification est
     *                         construite à partir d'elle au lieu d'être redemandée au LLM.
     */
    private List<TrainingPair> generatePairsFromChunk(String chunkText, String sourceFile,
                                                      String documentCategory) {
        List<TrainingPair> pairs = new ArrayList<>();

        // 1. Paire Question / Réponse
        String qaPrompt = """
                À partir du texte suivant, génère exactement une question pertinente et sa réponse.
                Le texte provient d'un document d'exploitation autoroutière.
                Réponds UNIQUEMENT en JSON valide avec les clés "question" et "answer".
                Ne mets aucun texte avant ou après le JSON.

                Texte:
                %s""".formatted(chunkText);

        String qaJson = llmChatClient.chat("Tu génères des paires d'entraînement pour un LLM.", qaPrompt);
        TrainingPair qaPair = parseQaPair(qaJson, chunkText, sourceFile, documentCategory);
        if (qaPair != null) pairs.add(qaPair);

        // 2. Paire Résumé
        String summaryPrompt = """
                Résume le texte suivant en 2-3 phrases concises.
                Le texte provient d'un document d'exploitation autoroutière.
                Réponds UNIQUEMENT en JSON valide avec les clés "instruction" et "summary".
                L'instruction doit demander un résumé du contenu.

                Texte:
                %s""".formatted(chunkText);

        String summaryJson = llmChatClient.chat("Tu génères des paires d'entraînement pour un LLM.", summaryPrompt);
        TrainingPair summaryPair = parseSummaryPair(summaryJson, chunkText, sourceFile, documentCategory);
        if (summaryPair != null) pairs.add(summaryPair);

        // 3. Paire Classification — réutilise le verdict du classifieur GED (R8) quand il
        // existe. Le document a déjà été classé une fois, sur un extrait représentatif de
        // l'ensemble ; le reclasser chunk par chunk coûtait un appel LLM sur trois et pouvait
        // contredire la fiche du document.
        TrainingPair classifPair = documentCategory != null
                ? classificationPairFromGed(chunkText, sourceFile, documentCategory)
                : classificationPairFromLlm(chunkText, sourceFile);
        if (classifPair != null) pairs.add(classifPair);

        return pairs;
    }

    /** Paire de classification adossée au verdict du classifieur documentaire. */
    private TrainingPair classificationPairFromGed(String chunkText, String sourceFile, String category) {
        return TrainingPair.of(
                "Classe le texte suivant dans la bonne catégorie : " + chunkText,
                "Catégorie : " + category,
                sourceFile, category, "classification", 0.9, category);
    }

    /** Repli pour un document non classifié : on demande la catégorie au LLM, comme avant. */
    private TrainingPair classificationPairFromLlm(String chunkText, String sourceFile) {
        String taxonomyBlock = taxonomy.stream().map(c -> "- " + c).collect(Collectors.joining("\n"));
        String classifPrompt = """
                Classe le texte suivant dans exactement UNE de ces catégories :
                %s

                Réponds UNIQUEMENT en JSON valide avec les clés "category" et "reason".

                Texte:
                %s""".formatted(taxonomyBlock, chunkText);

        String classifJson = llmChatClient.chat("Tu es un classificateur de documents.", classifPrompt);
        return parseClassificationPair(classifJson, chunkText, sourceFile);
    }

    private TrainingPair parseQaPair(String json, String chunkText, String source, String documentCategory) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String question = node.get("question").asText();
            String answer = node.get("answer").asText();
            boolean wellFormed = question.length() >= 10 && answer.length() >= 20;
            double confidence = wellFormed ? groundedConfidence(answer, chunkText) : 0.4;
            return TrainingPair.of(question, answer, source, "qa", "question_answer", confidence, documentCategory);
        } catch (Exception e) {
            log.debug("Parsing QA échoué: {}", e.getMessage());
            return null;
        }
    }

    private TrainingPair parseSummaryPair(String json, String chunkText, String source, String documentCategory) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String instruction = node.get("instruction").asText();
            String summary = node.get("summary").asText();
            boolean wellFormed = instruction.length() >= 10 && summary.length() >= 30;
            double confidence = wellFormed ? groundedConfidence(summary, chunkText) : 0.4;
            return TrainingPair.of(instruction, summary, source, "summary", "summarization", confidence, documentCategory);
        } catch (Exception e) {
            log.debug("Parsing résumé échoué: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Score de confiance <b>ancré dans la source</b> plutôt qu'un simple test de longueur.
     *
     * <p>Mesure la part des mots de contenu de la réponse présents dans le chunk d'origine
     * (proxy d'ancrage / anti-hallucination), puis la combine à une base : une réponse bien
     * formée et fortement ancrée tend vers 1.0 ; bien formée mais peu ancrée (donc potentiellement
     * inventée) reste vers 0.6, et passe sous les seuils stricts (0.85 / 0.9) des recettes.</p>
     */
    private double groundedConfidence(String answer, String chunkText) {
        double grounding = grounding(answer, chunkText);   // 0..1
        double score = 0.6 + 0.4 * grounding;
        return Math.max(0.0, Math.min(1.0, score));
    }

    /** Fraction des mots de contenu (>3 lettres) de {@code text} qui apparaissent dans {@code source}. */
    private double grounding(String text, String source) {
        Set<String> textWords = contentWords(text);
        if (textWords.isEmpty()) return 0.0;
        Set<String> sourceWords = contentWords(source);
        if (sourceWords.isEmpty()) return 0.0;
        long hits = textWords.stream().filter(sourceWords::contains).count();
        return (double) hits / textWords.size();
    }

    private Set<String> contentWords(String text) {
        Set<String> words = new java.util.HashSet<>();
        for (String w : text.toLowerCase().split("[^\\p{L}\\p{Nd}]+")) {
            if (w.length() > 3) words.add(w);
        }
        return words;
    }

    /**
     * Génère un exemple « négatif » : une question plausible du domaine dont la réponse
     * n'est PAS dans le chunk, associée à un refus. Entraîne le modèle à dire « je ne sais pas »
     * au lieu d'inventer — le levier le plus efficace contre l'hallucination dans un assistant RAG.
     */
    private TrainingPair generateRefusalPair(String chunkText, String source, int rotation,
                                             String documentCategory) {
        String prompt = """
                À partir du texte suivant (document d'exploitation autoroutière), génère UNE question
                plausible du même domaine dont la réponse N'EST PAS contenue dans ce texte.
                La question doit paraître naturelle mais porter sur un détail absent du texte.
                Réponds UNIQUEMENT en JSON valide avec la clé "question". Aucun texte autour.

                Texte:
                %s""".formatted(chunkText);
        try {
            String json = llmChatClient.chat("Tu génères des questions de test pour un assistant.", prompt);
            JsonNode node = mapper.readTree(extractJson(json));
            String question = node.get("question").asText();
            if (question == null || question.strip().length() < 10) return null;
            String refusal = REFUSAL_ANSWERS.get(Math.floorMod(rotation, REFUSAL_ANSWERS.size()));
            return TrainingPair.of(question.strip(), refusal, source, "negative", "refusal", 0.9, documentCategory);
        } catch (Exception e) {
            log.debug("Génération refus échouée: {}", e.getMessage());
            return null;
        }
    }

    private TrainingPair parseClassificationPair(String json, String chunkText, String source) {
        try {
            JsonNode node = mapper.readTree(extractJson(json));
            String category = node.get("category").asText().toLowerCase().trim();
            // Hors taxonomie = étiquette peu fiable : la confiance basse la fait tomber sous
            // les seuils stricts des recettes de fine-tuning.
            double confidence = taxonomy.contains(category) ? 0.8 : 0.4;
            String instruction = "Classe le texte suivant dans la bonne catégorie : " + chunkText;
            String response = "Catégorie : " + category;
            // documentCategory reste null : cette catégorie est devinée chunk par chunk, elle
            // ne vaut pas le verdict du classifieur sur le document entier.
            return TrainingPair.of(instruction, response, source, category, "classification", confidence);
        } catch (Exception e) {
            log.debug("Parsing classification échoué: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Supprime les paires en double (même instruction + même réponse), en conservant la
     * première occurrence. Renvoie la liste des paires uniques. Évite de sur-pondérer un
     * contenu répété lors du fine-tuning.
     */
    private List<TrainingPair> deduplicate(List<TrainingPair> pairs) {
        Set<String> seen = new java.util.HashSet<>();
        List<TrainingPair> unique = new ArrayList<>();
        for (TrainingPair pair : pairs) {
            String user = pair.conversations().stream()
                    .filter(m -> "user".equals(m.role())).map(TrainingPair.Message::content)
                    .findFirst().orElse("");
            String assistant = pair.conversations().stream()
                    .filter(m -> "assistant".equals(m.role())).map(TrainingPair.Message::content)
                    .findFirst().orElse("");
            String key = (user + "\0" + assistant).toLowerCase().strip();
            if (seen.add(key)) {
                unique.add(pair);
            }
        }
        return unique;
    }

    private void persistPairs() {
        try {
            Files.createDirectories(pairsFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(pairsFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (TrainingPair pair : generatedPairs) {
                    writer.write(mapper.writeValueAsString(pair));
                    writer.newLine();
                }
            }
            log.info("Paires SFT persistées: {} → {}", generatedPairs.size(), pairsFile);
        } catch (Exception e) {
            log.warn("Impossible de persister les paires SFT: {}", e.getMessage());
        }
    }

    /**
     * Extrait le premier bloc JSON valide d'une réponse LLM.
     * Gère les blocs markdown ```json ... ``` et le texte autour.
     */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) return "{}";

        // Strip markdown code fences (```json ... ``` or ``` ... ```)
        String cleaned = text.strip();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).strip();
            }
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return "{}";
    }
}
