package fr.spectra.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaPayloadMapperTest {

    private final KafkaPayloadMapper mapper = new KafkaPayloadMapper();

    private byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private String text(KafkaPayloadMapper.Mapped m) { return new String(m.content(), StandardCharsets.UTF_8); }

    @Test
    void noContentField_returnsRawPayload() {
        byte[] raw = bytes("{\"body\":\"bonjour\"}");
        KafkaPayloadMapper.Mapped m = mapper.map(raw, null, List.of());
        assertThat(m.content()).isEqualTo(raw);
        assertThat(m.metadata()).isEmpty();
    }

    @Test
    void extractsSimpleField() {
        KafkaPayloadMapper.Mapped m = mapper.map(bytes("{\"body\":\"contenu utile\",\"x\":1}"), "body", List.of());
        assertThat(text(m)).isEqualTo("contenu utile");
    }

    @Test
    void extractsFieldByJsonPointer() {
        KafkaPayloadMapper.Mapped m = mapper.map(bytes("{\"data\":{\"text\":\"profond\"}}"), "/data/text", List.of());
        assertThat(text(m)).isEqualTo("profond");
    }

    @Test
    void copiesMetadataFields() {
        KafkaPayloadMapper.Mapped m = mapper.map(
                bytes("{\"body\":\"txt\",\"statut\":\"clos\",\"auteur\":\"alice\"}"),
                "body", List.of("statut", "auteur"));
        assertThat(m.metadata()).containsEntry("statut", "clos").containsEntry("auteur", "alice");
    }

    @Test
    void objectContentField_serializedAsJson() {
        KafkaPayloadMapper.Mapped m = mapper.map(bytes("{\"payload\":{\"a\":1}}"), "payload", List.of());
        assertThat(text(m)).contains("\"a\":1");
    }

    @Test
    void invalidJson_fallsBackToRaw() {
        byte[] raw = bytes("ceci n'est pas du json");
        KafkaPayloadMapper.Mapped m = mapper.map(raw, "body", List.of());
        assertThat(m.content()).isEqualTo(raw);
        assertThat(m.metadata()).isEmpty();
    }

    @Test
    void missingContentField_yieldsEmptyText() {
        KafkaPayloadMapper.Mapped m = mapper.map(bytes("{\"other\":\"x\"}"), "body", List.of());
        assertThat(text(m)).isEmpty();
    }
}
