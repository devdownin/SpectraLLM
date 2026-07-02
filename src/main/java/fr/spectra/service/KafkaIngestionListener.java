package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * Consumer Kafka — enrichit le RAG au fil de l'eau à partir de messages « vivants ».
 *
 * <p>Approche <b>payload brut par défaut</b> : la valeur du message est transmise telle quelle
 * à l'extracteur, routé par l'extension logique ({@code spectra.kafka.format}, ex. {@code json}).
 * Aucun mapping de champ n'est appliqué.</p>
 *
 * <p>Identité RAG : la <b>clé du message</b> devient une identité métier stable
 * ({@code sourceFile = kafka://<topic>/<key>}) qui permet l'upsert (remplacer la version
 * précédente). Sans clé, on retombe sur {@code kafka://<topic>/<partition>-<offset>}
 * (append-only, pas d'upsert). Une valeur nulle agit comme tombstone (suppression).</p>
 *
 * <p>Commit manuel après indexation réussie (at-least-once) ; en cas d'échec, l'exception
 * remonte à l'error handler qui réessaie puis bascule vers le Dead Letter Topic.</p>
 */
@Service
@ConditionalOnProperty(prefix = "spectra.kafka", name = "enabled", havingValue = "true")
public class KafkaIngestionListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaIngestionListener.class);

    private final IngestionService ingestionService;
    private final SpectraProperties.KafkaProperties cfg;

    public KafkaIngestionListener(IngestionService ingestionService, SpectraProperties properties) {
        this.ingestionService = ingestionService;
        this.cfg = properties.kafka();
    }

    @KafkaListener(
            topics = "#{'${spectra.kafka.topics:}'.split(',')}",
            groupId = "${spectra.kafka.group-id:spectra-ingestion}",
            containerFactory = "spectraKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        String sourceKey = buildSourceKey(record);
        String logicalName = sourceKey + "." + cfg.effectiveFormat();
        String offsetRef = record.topic() + "/" + record.partition() + "/" + record.offset();
        // value == null → tombstone : contenu vide → suppression dans upsertFromStream.
        byte[] value = record.value() != null ? record.value() : new byte[0];

        try {
            IngestionService.UpsertResult result = ingestionService.upsertFromStream(
                    sourceKey, logicalName, new ByteArrayInputStream(value),
                    cfg.effectiveCollection(), offsetRef);
            ack.acknowledge(); // commit APRÈS indexation réussie → at-least-once
            log.debug("Message Kafka traité: {} → {}", offsetRef, result.kind());
        } catch (Exception e) {
            // Remonte à l'error handler (retries + Dead Letter Topic).
            throw new ListenerExecutionFailedException(
                    "Échec de l'upsert streaming pour " + sourceKey + " (offset " + offsetRef + ")", e);
        }
    }

    /** {@code kafka://<topic>/<key>} si clé présente, sinon {@code kafka://<topic>/<partition>-<offset>}. */
    static String buildSourceKey(ConsumerRecord<String, byte[]> record) {
        if (record.key() != null && !record.key().isBlank()) {
            return "kafka://" + record.topic() + "/" + record.key();
        }
        return "kafka://" + record.topic() + "/" + record.partition() + "-" + record.offset();
    }
}
