package fr.spectra.service.reranker;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encodage par lots des paires (requête, passage) — la dernière étape avant l'inférence, et la
 * plus silencieuse en cas d'erreur.
 *
 * <p>Vérifié sur un tokenizer WordPiece jouet (cf. {@link HuggingFaceTokenizerSmokeTest}) plutôt
 * que sur le vrai modèle : ce qui se teste ici n'est pas le vocabulaire mais la mécanique de
 * rembourrage et de masquage, identique quel que soit le modèle. Un masque d'attention faux
 * rend les scores dépendants de la composition du lot — le modèle « marche » et classe mal.
 */
class PairBatchEncoderTest {

    private static final String TOKENIZER_JSON = """
            {
              "version": "1.0", "truncation": null, "padding": null,
              "added_tokens": [
                {"id": 0, "content": "[PAD]", "special": true, "single_word": false,
                 "lstrip": false, "rstrip": false, "normalized": false},
                {"id": 1, "content": "[UNK]", "special": true, "single_word": false,
                 "lstrip": false, "rstrip": false, "normalized": false},
                {"id": 2, "content": "[CLS]", "special": true, "single_word": false,
                 "lstrip": false, "rstrip": false, "normalized": false},
                {"id": 3, "content": "[SEP]", "special": true, "single_word": false,
                 "lstrip": false, "rstrip": false, "normalized": false}
              ],
              "normalizer": {"type": "BertNormalizer", "clean_text": true,
                             "handle_chinese_chars": true, "strip_accents": null,
                             "lowercase": true},
              "pre_tokenizer": {"type": "BertPreTokenizer"},
              "post_processor": {
                "type": "TemplateProcessing",
                "single": [
                  {"SpecialToken": {"id": "[CLS]", "type_id": 0}},
                  {"Sequence": {"id": "A", "type_id": 0}},
                  {"SpecialToken": {"id": "[SEP]", "type_id": 0}}
                ],
                "pair": [
                  {"SpecialToken": {"id": "[CLS]", "type_id": 0}},
                  {"Sequence": {"id": "A", "type_id": 0}},
                  {"SpecialToken": {"id": "[SEP]", "type_id": 0}},
                  {"Sequence": {"id": "B", "type_id": 1}},
                  {"SpecialToken": {"id": "[SEP]", "type_id": 1}}
                ],
                "special_tokens": {
                  "[CLS]": {"id": "[CLS]", "ids": [2], "tokens": ["[CLS]"]},
                  "[SEP]": {"id": "[SEP]", "ids": [3], "tokens": ["[SEP]"]}
                }
              },
              "decoder": {"type": "WordPiece", "prefix": "##", "cleanup": true},
              "model": {
                "type": "WordPiece", "unk_token": "[UNK]", "continuing_subword_prefix": "##",
                "max_input_chars_per_word": 100,
                "vocab": {"[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3,
                          "panne": 4, "autoroute": 5, "sur": 6, "vehicule": 7, "bande": 8,
                          "arret": 9, "urgence": 10}
              }
            }
            """;

    @TempDir
    static Path dir;
    private static HuggingFaceTokenizer tokenizer;
    private static PairBatchEncoder encoder;

    @BeforeAll
    static void setUp() throws IOException {
        Path file = dir.resolve("tokenizer.json");
        Files.writeString(file, TOKENIZER_JSON, StandardCharsets.UTF_8);
        tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(file)
                .optTruncation(true)
                .optMaxLength(64)
                .optPadding(false)
                .build();
        encoder = new PairBatchEncoder(tokenizer);
    }

    @AfterAll
    static void tearDown() {
        if (tokenizer != null) {
            tokenizer.close();
        }
    }

    @Test
    void rembourreAuPlusLongDuLotEtNonAMaxLength() {
        // Rembourrer à maxLength (64 ici) ferait calculer le modèle sur des dizaines de
        // positions vides à chaque passage. Le plus long du lot suffit et donne le même résultat.
        PairBatchEncoder.EncodedBatch batch = encoder.encode("panne",
                List.of("vehicule", "vehicule sur bande arret urgence"));

        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.paddedLength()).isEqualTo(9);   // [CLS] panne [SEP] v s b a u [SEP]
        assertThat(batch.paddedLength()).isLessThan(64);
        assertThat(batch.inputIds()[0]).hasSize(9);
        assertThat(batch.inputIds()[1]).hasSize(9);
    }

    @Test
    void masqueDAttentionAZeroSurLeRembourrage() {
        // LE point critique : sans masque à 0, le modèle « voit » les tokens de remplissage et
        // le score d'un passage dépend alors des autres passages du lot.
        PairBatchEncoder.EncodedBatch batch = encoder.encode("panne",
                List.of("vehicule", "vehicule sur bande arret urgence"));

        long[] court = batch.attentionMask()[0];
        assertThat(court).containsExactly(1, 1, 1, 1, 1, 0, 0, 0, 0);
        assertThat(batch.attentionMask()[1]).containsOnly(1);

        // Les positions rembourrées portent l'id de padding (0) et le segment 0.
        assertThat(batch.inputIds()[0][8]).isZero();
        assertThat(batch.tokenTypeIds()[0][8]).isZero();
    }

    @Test
    void segmentsDistinguesEntreRequeteEtPassage() {
        PairBatchEncoder.EncodedBatch batch = encoder.encode("panne autoroute", List.of("vehicule"));

        // [CLS] panne autoroute [SEP] | vehicule [SEP]
        assertThat(batch.inputIds()[0]).containsExactly(2, 4, 5, 3, 7, 3);
        assertThat(batch.tokenTypeIds()[0]).containsExactly(0, 0, 0, 0, 1, 1);
    }

    @Test
    void lotDUnSeulPassage_aucunRembourrage() {
        PairBatchEncoder.EncodedBatch batch = encoder.encode("panne", List.of("vehicule"));

        assertThat(batch.attentionMask()[0]).containsOnly(1);
    }

    @Test
    void lotVide_batchVide() {
        PairBatchEncoder.EncodedBatch batch = encoder.encode("panne", List.of());

        assertThat(batch.size()).isZero();
        assertThat(batch.paddedLength()).isZero();
    }

    @Test
    void troncatureBorneeAMaxLength() throws IOException {
        // Une paire plus longue que maxLength doit être tronquée, pas rejetée — et la longueur
        // du lot ne doit jamais dépasser la borne.
        Path file = dir.resolve("tokenizer.json");
        try (HuggingFaceTokenizer courtTokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(file).optTruncation(true).optMaxLength(6).optPadding(false)
                .build()) {
            PairBatchEncoder.EncodedBatch batch = new PairBatchEncoder(courtTokenizer)
                    .encode("panne sur autoroute",
                            List.of("vehicule sur bande arret urgence panne autoroute"));

            assertThat(batch.paddedLength()).isLessThanOrEqualTo(6);
        }
    }
}
