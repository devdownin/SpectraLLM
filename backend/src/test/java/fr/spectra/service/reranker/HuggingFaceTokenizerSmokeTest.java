package fr.spectra.service.reranker;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie que la tokenisation HuggingFace fonctionne <b>hors ligne</b>, sur un
 * {@code tokenizer.json} local.
 *
 * <p>C'est le prérequis silencieux du reranker exécuté dans la JVM : un cross-encoder n'est
 * qu'un BERT à tête de classification, et si les {@code input_ids} diffèrent de ceux produits
 * côté Python, les scores se déplacent sans que rien ne le signale. Le binding utilisé est le
 * JNI de la bibliothèque Rust {@code tokenizers} — la même que {@code transformers} — donc
 * l'égalité est structurelle, à condition que la bibliothèque native se charge sans aller la
 * chercher sur le réseau. Ce test le prouve : il n'utilise qu'un gabarit écrit à la main.
 *
 * <p>Deux propriétés sont contrôlées, toutes deux critiques pour une paire (requête, passage) :
 * l'encodage de la paire en une seule séquence {@code [CLS] A [SEP] B [SEP]}, et les
 * {@code token_type_ids} qui distinguent les deux segments (0 pour la requête, 1 pour le
 * passage). Une erreur sur l'un ou l'autre produit un modèle qui « marche » et classe mal.
 */
class HuggingFaceTokenizerSmokeTest {

    /**
     * Tokenizer WordPiece minimal, façon BERT : vocabulaire jouet et post-traitement de paire.
     * Suffisant pour valider le chargement natif et la convention d'encodage.
     */
    private static final String TOKENIZER_JSON = """
            {
              "version": "1.0",
              "truncation": null,
              "padding": null,
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
                "type": "WordPiece",
                "unk_token": "[UNK]",
                "continuing_subword_prefix": "##",
                "max_input_chars_per_word": 100,
                "vocab": {
                  "[PAD]": 0, "[UNK]": 1, "[CLS]": 2, "[SEP]": 3,
                  "panne": 4, "autoroute": 5, "sur": 6, "vehicule": 7, "bande": 8
                }
              }
            }
            """;

    private static Path writeTokenizer(Path dir) throws IOException {
        Path file = dir.resolve("tokenizer.json");
        Files.writeString(file, TOKENIZER_JSON, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void tokeniseUnePaireHorsLigne(@TempDir Path dir) throws IOException {
        try (HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(writeTokenizer(dir))) {
            Encoding encoding = tokenizer.encode("panne sur autoroute", "vehicule sur bande");

            long[] ids = encoding.getIds();
            long[] typeIds = encoding.getTypeIds();
            long[] attention = encoding.getAttentionMask();

            // [CLS] panne sur autoroute [SEP] vehicule sur bande [SEP]
            assertThat(ids).containsExactly(2, 4, 6, 5, 3, 7, 6, 8, 3);
            // Segment 0 = requête (jusqu'au premier [SEP] inclus), segment 1 = passage.
            assertThat(typeIds).containsExactly(0, 0, 0, 0, 0, 1, 1, 1, 1);
            assertThat(attention).containsOnly(1);
        }
    }

    @Test
    void motInconnuRemplaceParUnk(@TempDir Path dir) throws IOException {
        // Un vocabulaire incomplet ne doit pas faire échouer l'encodage : le modèle réel a
        // 250 k entrées, mais le comportement de repli doit rester celui de HuggingFace.
        try (HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(writeTokenizer(dir))) {
            Encoding encoding = tokenizer.encode("panne", "zzzz");

            assertThat(encoding.getIds()).containsExactly(2, 4, 3, 1, 3);
        }
    }
}
