package fr.spectra.service.reranker;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.RerankerClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reranking Cross-Encoder exécuté <b>dans la JVM</b>, via ONNX Runtime.
 *
 * <p>Remplace l'aller-retour HTTP vers le microservice Python {@code services/reranker}
 * (cf. {@code docs/process/audit-python-java.fr.md}, lot 2). Un cross-encoder n'est qu'un
 * encodeur à tête de classification : on tokenise la paire (requête, passage), un passage avant,
 * un logit. Rien là-dedans n'exige Python.
 *
 * <p><b>ONNX Runtime est utilisé directement</b>, sans passer par l'abstraction de DJL : le
 * barème des scores, la troncature de la paire et le choix des entrées fournies au graphe
 * doivent rester explicites. Ce sont exactement les réglages qui déplacent un classement sans
 * lever la moindre erreur — les confier à une couche de conventions rendrait la comparaison à la
 * référence de parité ininterprétable.
 *
 * <p><b>Activation par défaut.</b> Le moteur applique une sigmoïde, comme
 * {@code sentence-transformers} sur un cross-encoder à un logit, afin que les
 * {@code rerankScores} publiés par l'API restent sur la même échelle qu'avant la migration.
 * {@code spectra.reranker.onnx.activation=logit} rend le logit brut.
 *
 * <p><b>En cas de modèle absent ou illisible</b>, le bean se crée quand même : {@code /api/status}
 * rapporte le reranker indisponible avec la cause, et {@code rerank} lève, ce qui fait retomber
 * {@code RagService} sur l'ordre vectoriel avec {@code rerankApplied=false}. Un service de
 * reranking optionnel ne doit pas empêcher l'application de démarrer — mais sa panne ne doit
 * jamais être silencieuse non plus.
 *
 * <p>Actif lorsque {@code spectra.reranker.enabled=true} et {@code spectra.reranker.engine=onnx}.
 */
@Service
@ConditionalOnExpression(
        "'${spectra.reranker.enabled:false}' == 'true' and '${spectra.reranker.engine:http}' == 'onnx'")
public class OnnxCrossEncoderReranker implements RerankerClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderReranker.class);

    /** Fichiers attendus dans le répertoire du modèle. */
    static final String MODEL_FILE = "model.onnx";
    static final String TOKENIZER_FILE = "tokenizer.json";

    /** Entrées possibles d'un encodeur de la famille BERT ; le graphe ne les déclare pas toutes. */
    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";

    private final Path modelDir;
    private final CrossEncoderScoring.Activation activation;
    private final int batchSize;

    private OrtEnvironment environment;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private PairBatchEncoder encoder;
    private Set<String> graphInputs = Set.of();
    /** Cause du non-chargement, publiée telle quelle : une panne muette serait pire qu'une panne. */
    private String loadError;

    public OnnxCrossEncoderReranker(SpectraProperties props) {
        SpectraProperties.OnnxRerankerProperties onnx = props.reranker() != null
                ? props.reranker().effectiveOnnx()
                : new SpectraProperties.OnnxRerankerProperties(null, null, null, null);
        this.modelDir = Path.of(onnx.effectiveModelPath()).toAbsolutePath().normalize();
        this.activation = CrossEncoderScoring.Activation.parse(onnx.effectiveActivation());
        this.batchSize = Math.max(1, onnx.effectiveBatchSize());
        load(onnx.effectiveMaxLength());
    }

    private void load(int maxLength) {
        Path model = modelDir.resolve(MODEL_FILE);
        Path tokenizerFile = modelDir.resolve(TOKENIZER_FILE);
        try {
            if (!Files.isRegularFile(model) || !Files.isRegularFile(tokenizerFile)) {
                throw new IllegalStateException(
                        "modèle de reranking introuvable — attendus " + MODEL_FILE + " et "
                                + TOKENIZER_FILE + " dans " + modelDir);
            }
            // Troncature « longest_first » à maxLength, comme CrossEncoder.predict côté Python.
            this.tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile)
                    .optTruncation(true)
                    .optMaxLength(maxLength)
                    .optPadding(false)          // le rembourrage est fait par lot, cf. PairBatchEncoder
                    .build();
            this.encoder = new PairBatchEncoder(tokenizer);
            this.environment = OrtEnvironment.getEnvironment();
            this.session = environment.createSession(model.toString(), new OrtSession.SessionOptions());
            this.graphInputs = Set.copyOf(session.getInputNames());
            this.loadError = null;
            log.info("Reranker ONNX chargé : {} (entrées={}, activation={}, maxLength={}, batch={})",
                    modelDir, graphInputs, activation, maxLength, batchSize);
        } catch (Exception e) {
            this.loadError = e.getMessage();
            log.error("Reranker ONNX indisponible : {}. Placez model.onnx et tokenizer.json dans {} "
                            + "(ou repassez sur spectra.reranker.engine=http). Le RAG continuera "
                            + "de fonctionner sans reranking.",
                    e.getMessage(), modelDir);
        }
    }

    boolean isLoaded() {
        return loadError == null && session != null;
    }

    /**
     * Un moteur dont l'artefact n'a pas pu être chargé n'est pas utilisable — et doit le dire
     * avant que {@code RagService} ne sur-extraie des candidats pour lui.
     */
    @Override
    public boolean isAvailable() {
        return isLoaded();
    }

    @Override
    public ServiceStatus checkHealth() {
        if (!isLoaded()) {
            return new ServiceStatus("reranker", modelDir.toString(), false,
                    "unavailable", 0, Map.of("engine", "onnx", "error", String.valueOf(loadError)));
        }
        return new ServiceStatus("reranker", modelDir.toString(), true, "ok", 0,
                Map.of("engine", "onnx", "activation", activation.name().toLowerCase(java.util.Locale.ROOT),
                        "inputs", graphInputs));
    }

    @Override
    public List<RankedResult> rerank(String query, List<String> documents, int topN) {
        // Vivier vide : rien à classer — même contrat que le client HTTP.
        if (documents.isEmpty()) {
            return List.of();
        }
        if (topN < 1) {
            // Le service Python rejetait en 422 (Pydantic Field(ge=1)) ; on refuse de même,
            // plutôt que de renvoyer un sous-ensemble arbitraire.
            throw new IllegalArgumentException("top_n doit être >= 1 (reçu " + topN + ")");
        }
        if (!isLoaded()) {
            throw new IllegalStateException("Reranker ONNX non chargé : " + loadError);
        }

        float[] logits = new float[documents.size()];
        for (int start = 0; start < documents.size(); start += batchSize) {
            int end = Math.min(start + batchSize, documents.size());
            float[] batch = scoreBatch(query, documents.subList(start, end));
            System.arraycopy(batch, 0, logits, start, batch.length);
        }
        return CrossEncoderScoring.rank(logits, topN, activation);
    }

    /** Un passage avant sur un lot de paires ; renvoie les logits bruts, dans l'ordre soumis. */
    private float[] scoreBatch(String query, List<String> documents) {
        PairBatchEncoder.EncodedBatch batch = encoder.encode(query, documents);
        long[] shape = {batch.size(), batch.paddedLength()};

        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try {
            // Ne fournir QUE les entrées déclarées par le graphe : les modèles de la famille
            // XLM-R (dont mMiniLMv2, le multilingue configuré par défaut) n'exposent pas
            // token_type_ids, et passer une entrée inconnue fait échouer la session.
            inputs.put(INPUT_IDS, tensor(batch.inputIds(), shape));
            if (graphInputs.contains(ATTENTION_MASK)) {
                inputs.put(ATTENTION_MASK, tensor(batch.attentionMask(), shape));
            }
            if (graphInputs.contains(TOKEN_TYPE_IDS)) {
                inputs.put(TOKEN_TYPE_IDS, tensor(batch.tokenTypeIds(), shape));
            }

            try (OrtSession.Result result = session.run(new HashMap<>(inputs))) {
                return readLogits(result, batch.size());
            }
        } catch (OrtException e) {
            throw new IllegalStateException("Échec de l'inférence de reranking : " + e.getMessage(), e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private OnnxTensor tensor(long[][] values, long[] shape) throws OrtException {
        LongBuffer buffer = LongBuffer.allocate((int) (shape[0] * shape[1]));
        for (long[] row : values) {
            buffer.put(row);
        }
        buffer.rewind();
        return OnnxTensor.createTensor(environment, buffer, shape);
    }

    /**
     * Lit la sortie du graphe, quelle que soit sa forme.
     *
     * <p>Un cross-encoder à une étiquette sort en {@code [lot, 1]} ; certains exports rendent
     * {@code [lot]}. Les deux sont acceptés, une autre forme est rejetée explicitement plutôt
     * que réduite à une valeur arbitraire — un modèle à plusieurs étiquettes n'est pas un
     * reranker, et le silence donnerait un classement plausible mais faux.
     */
    private static float[] readLogits(OrtSession.Result result, int expected) throws OrtException {
        Object value = result.get(0).getValue();
        float[] logits = new float[expected];
        switch (value) {
            // `case null` explicite : sans lui, un switch par motif lève une NullPointerException
            // avant d'atteindre `default`, et le message d'erreur soigné n'aurait jamais servi.
            case null -> throw new IllegalStateException(
                    "Sortie de reranking absente : le graphe n'a produit aucune valeur.");
            case float[][] matrix -> {
                if (matrix.length != expected || matrix[0].length != 1) {
                    throw new IllegalStateException(
                            "Sortie de reranking inattendue : [" + matrix.length + ", "
                                    + matrix[0].length + "], attendu [" + expected + ", 1]. "
                                    + "Ce modèle n'est pas un cross-encoder à une étiquette.");
                }
                for (int i = 0; i < expected; i++) {
                    logits[i] = matrix[i][0];
                }
            }
            case float[] vector -> {
                if (vector.length != expected) {
                    throw new IllegalStateException(
                            "Sortie de reranking inattendue : [" + vector.length
                                    + "], attendu [" + expected + "]");
                }
                System.arraycopy(vector, 0, logits, 0, expected);
            }
            default -> throw new IllegalStateException(
                    "Type de sortie de reranking non pris en charge : " + value.getClass().getName());
        }
        return logits;
    }

    @Override
    @PreDestroy
    public void close() {
        closeQuietly(session, "session ONNX");
        closeQuietly(tokenizer, "tokenizer");
        session = null;
        tokenizer = null;
    }

    private static void closeQuietly(AutoCloseable closeable, String what) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            log.warn("Fermeture du {} impossible : {}", what, e.getMessage());
        }
    }
}
