package fr.spectra.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de StringListConverter — sérialisation JSON JPA.
 */
class StringListConverterTest {

    private final StringListConverter converter = new StringListConverter();

    // ── convertToDatabaseColumn ───────────────────────────────────────────────

    @Test
    void toDatabaseColumn_simpleList_returnsJsonArray() {
        String json = converter.convertToDatabaseColumn(List.of("a", "b", "c"));
        assertThat(json).isEqualTo("[\"a\",\"b\",\"c\"]");
    }

    @Test
    void toDatabaseColumn_emptyList_returnsEmptyArray() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isEqualTo("[]");
    }

    @Test
    void toDatabaseColumn_nullList_returnsEmptyArray() {
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("[]");
    }

    @Test
    void toDatabaseColumn_singleElement_returnsJsonArray() {
        assertThat(converter.convertToDatabaseColumn(List.of("seul"))).isEqualTo("[\"seul\"]");
    }

    // ── convertToEntityAttribute ──────────────────────────────────────────────

    @Test
    void toEntityAttribute_jsonArray_returnsList() {
        List<String> list = converter.convertToEntityAttribute("[\"x\",\"y\",\"z\"]");
        assertThat(list).containsExactly("x", "y", "z");
    }

    @Test
    void toEntityAttribute_emptyArray_returnsEmptyList() {
        assertThat(converter.convertToEntityAttribute("[]")).isEmpty();
    }

    @Test
    void toEntityAttribute_nullString_returnsEmptyList() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }

    @Test
    void toEntityAttribute_blankString_returnsEmptyList() {
        assertThat(converter.convertToEntityAttribute("   ")).isEmpty();
    }

    @Test
    void toEntityAttribute_invalidJson_returnsEmptyList() {
        assertThat(converter.convertToEntityAttribute("not-json-at-all")).isEmpty();
    }

    // ── Roundtrip ─────────────────────────────────────────────────────────────

    @Test
    void roundtrip_listsAreEquivalent() {
        List<String> original = List.of("fichier.pdf", "document.xml", "data.json");
        String json = converter.convertToDatabaseColumn(original);
        List<String> restored = converter.convertToEntityAttribute(json);
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void roundtrip_withSpecialCharacters() {
        List<String> original = List.of("chemin/avec/slash", "tiret-composé", "espace blanc");
        String json = converter.convertToDatabaseColumn(original);
        List<String> restored = converter.convertToEntityAttribute(json);
        assertThat(restored).isEqualTo(original);
    }
}
