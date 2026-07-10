package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Consumer Kafka — enrichit le RAG au fil de l'eau à partir de messages « vivants ».
 *
 * <p>Par défaut, <b>payload brut</b> : la valeur du message est transmise telle quelle à
 * l'extracteur, routé par l'extension logique ({@code spectra.kafka.format}). Si
 * {@code spectra.kafka.content-field} est configuré, seul ce champ JSON est indexé et les
 * {@code metadata-fields} sont recopiés dans les métadonnées (filtrage/traçabilité).</p>
 *
 * <p>Identité RAG : la <b>clé du message</b> devient une identité métier stable
 * ({@code sourceFile = kafka://<topic>/<key>}) qui permet l'upsert. Une valeur nulle agit
 * comme tombstone (suppression). Commit manuel après indexation (at-least-once) ; en cas
 * d'échec, l'exception remonte à l'error handler (retries puis Dead Letter Topic).</p>
 *
 * <p>Chaque message porte des métadonnées de <b>fraîcheur</b> : {@code ingestedAt} (ajouté par
 * le pipeline) et {@code eventTime} (horodatage Kafka), exploitables pour un filtrage/tri par
 * récence au retrieval.</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.kafka", name = "enabled", havingValue = "true")
public class KafkaIngestionListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaIngestionListener.class);

    private final IngestionService ingestionService;
    private final SpectraProperties.KafkaProperties cfg;
    private final KafkaPayloadMapper payloadMapper;
    private final MeterRegistry meterRegistry;

    public KafkaIngestionListener(IngestionService ingestionService, SpectraProperties properties,
                                  MeterRegistry meterRegistry) {
        this.ingestionService = ingestionService;
        this.cfg = properties.kafka();
        this.payloadMapper = new KafkaPayloadMapper();
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "#{'${spectra.kafka.topics:}'.split(',')}",
            groupId = "${spectra.kafka.group-id:spectra-ingestion}",
            containerFactory = "spectraKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        String sourceKey = buildSourceKey(record);
        String offsetRef = record.topic() + "/" + record.partition() + "/" + record.offset();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            IngestionService.UpsertResult result = process(record, sourceKey, offsetRef);
            ack.acknowledge(); // commit APRÈS indexation réussie → at-least-once
            sample.stop(meterRegistry.timer("spectra.kafka.processing", "topic", record.topic()));
            count(record.topic(), result.kind().name().toLowerCase());
            log.debug("Message Kafka traité: {} → {}", offsetRef, result.kind());
        } catch (Exception e) {
            sample.stop(meterRegistry.timer("spectra.kafka.processing", "topic", record.topic()));
            count(record.topic(), "failed");
            // Remonte à l'error handler (retries + Dead Letter Topic).
            throw new ListenerExecutionFailedException(
                    "Échec de l'upsert streaming pour " + sourceKey + " (offset " + offsetRef + ")", e);
        }
    }

    private IngestionService.UpsertResult process(ConsumerRecord<String, byte[]> record,
                                                  String sourceKey, String offsetRef) throws Exception {
        byte[] rawValue = record.value() != null ? record.value() : new byte[0];

        // Mapping optionnel : extraction d'un champ JSON comme contenu + métadonnées de champs.
        KafkaPayloadMapper.Mapped mapped = payloadMapper.map(
                rawValue, cfg.effectiveContentField(), cfg.effectiveMetadataFields());
        // Un champ extrait est du texte simple → routage vers l'extracteur texte.
        String format = cfg.hasFieldMapping() ? "txt" : cfg.effectiveFormat();
        String logicalName = sourceKey + "." + format;

        Map<String, String> extraMeta = new HashMap<>(mapped.metadata());
        extraMeta.put("eventTime", Instant.ofEpochMilli(record.timestamp()).toString());

        return ingestionService.upsertFromStream(
                sourceKey, logicalName, new ByteArrayInputStream(mapped.content()),
                cfg.effectiveCollection(), offsetRef, extraMeta);
    }

    private void count(String topic, String result) {
        meterRegistry.counter("spectra.kafka.messages", "topic", topic, "result", result).increment();
    }

    /** {@code kafka://<topic>/<key>} si clé présente, sinon {@code kafka://<topic>/<partition>-<offset>}. */
    static String buildSourceKey(ConsumerRecord<String, byte[]> record) {
        if (record.key() != null && !record.key().isBlank()) {
            return "kafka://" + record.topic() + "/" + record.key();
        }
        return "kafka://" + record.topic() + "/" + record.partition() + "-" + record.offset();
    }
}
