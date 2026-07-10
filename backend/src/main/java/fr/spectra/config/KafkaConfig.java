package fr.spectra.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration Kafka de l'ingestion streaming — active uniquement si
 * {@code spectra.kafka.enabled=true}. Sans ce flag, aucun bean Kafka n'est créé et
 * l'application démarre exactement comme avant (pas de connexion à un broker).
 *
 * <p>Choix structurants :</p>
 * <ul>
 *   <li><b>Commit manuel</b> ({@link ContainerProperties.AckMode#MANUAL}) : l'offset n'est
 *       validé qu'après indexation réussie → garantie <i>at-least-once</i>. Les rejeux sont
 *       absorbés par l'idempotence de {@code upsertFromStream} (hash de contenu).</li>
 *   <li><b>Dead Letter Topic</b> : après quelques tentatives, un message inextractable est
 *       publié sur {@code <topic>.DLT} au lieu de bloquer la partition indéfiniment.</li>
 *   <li><b>Valeur en octets</b> : le payload brut est transmis tel quel à l'extracteur
 *       (routage par extension), sans hypothèse de format côté désérialiseur.</li>
 * </ul>
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "spectra.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private final SpectraProperties.KafkaProperties cfg;

    public KafkaConfig(SpectraProperties properties) {
        this.cfg = properties.kafka() != null ? properties.kafka()
                : new SpectraProperties.KafkaProperties(true, null, null, null, null, null, null, null, null, null, null, null, null, null);
        log.info("Kafka streaming ingestion ACTIVÉ — brokers={}, topics={}, group={}, collection={}",
                cfg.effectiveBootstrapServers(), cfg.effectiveTopics(), cfg.effectiveGroupId(), cfg.effectiveCollection());
    }

    private void applySecurity(Map<String, Object> props) {
        props.put("security.protocol", cfg.effectiveSecurityProtocol());
        if (cfg.saslMechanism() != null) props.put("sasl.mechanism", cfg.saslMechanism());
        if (cfg.saslJaasConfig() != null) props.put("sasl.jaas.config", cfg.saslJaasConfig());
    }

    @Bean
    public ConsumerFactory<String, byte[]> spectraKafkaConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.effectiveBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, cfg.effectiveGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, cfg.effectiveMaxPollRecords());
        applySecurity(props);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ProducerFactory<String, byte[]> spectraKafkaProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.effectiveBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        applySecurity(props);
        return new DefaultKafkaProducerFactory<>(props);
    }

    /** Template utilisé par le Dead Letter Publishing Recoverer pour router les messages en échec. */
    @Bean
    public KafkaTemplate<String, byte[]> spectraKafkaTemplate(ProducerFactory<String, byte[]> pf) {
        return new KafkaTemplate<>(pf);
    }

    /**
     * Error handler : réessaie quelques fois (backoff fixe) puis publie sur le Dead Letter Topic
     * {@code <topic>.DLT} — un message empoisonné ne bloque jamais la partition.
     */
    @Bean
    public DefaultErrorHandler spectraKafkaErrorHandler(KafkaTemplate<String, byte[]> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
        // 2 nouvelles tentatives (3 essais au total) espacées d'1 s avant bascule en DLT.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2L));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> spectraKafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(cfg.effectiveConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
