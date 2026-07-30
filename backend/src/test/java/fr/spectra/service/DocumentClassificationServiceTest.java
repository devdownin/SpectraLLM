package fr.spectra.service;

import fr.spectra.config.SpectraProperties;
import fr.spectra.persistence.IngestedFileEntity;
import fr.spectra.persistence.IngestedFileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires du classifieur documentaire (R8) : construction de l'extrait,
 * parsing tolérant de la réponse du modèle, politique de taxonomie et persistance.
 */
class DocumentClassificationServiceTest {

    private IngestedFileRepository fileRepo;
    private GedService             gedService;
    private ChromaDbClient         chroma;
    private LlmChatClient          chat;

    @BeforeEach
    void setUp() {
        fileRepo   = mock(IngestedFileRepository.class);
        gedService = mock(GedService.class);
        chroma     = mock(ChromaDbClient.class);
        chat       = mock(LlmChatClient.class);
        when(chat.getActiveModel()).thenReturn("phi-4-mini");
        when(chroma.resolveCollectionIdUnchecked(anyString())).thenReturn("col-id");
    }

    /** Service configuré avec la taxonomie par défaut, sauf surcharge explicite. */
    private DocumentClassificationService service(SpectraProperties.ClassificationProperties cfg) {
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.classification()).thenReturn(cfg);
        when(props.chromadb()).thenReturn(
                new SpectraProperties.ChromaDbProperties(null, "spectra_documents"));
        return new DocumentClassificationService(fileRepo, gedService, chroma, chat, props);
    }

    private DocumentClassificationService defaultService() {
        return service(SpectraProperties.ClassificationProperties.defaults());
    }

    private static SpectraProperties.ClassificationProperties config(
            Boolean enabled, Boolean openTaxonomy, Integer maxCategories, Double minConfidence) {
        return new SpectraProperties.ClassificationProperties(
                enabled, null, null, openTaxonomy, maxCategories, minConfidence, null, null, null);
    }

    private static IngestedFileEntity doc(String sha) {
        return new IngestedFileEntity(sha, "consignes.pdf", "PDF",
                Instant.now(), 12, "spectra_documents", 0.8);
    }

    // ── Parsing de la réponse du modèle ──────────────────────────────────────

    @Test
    void parseVerdict_readsLabelsConfidencesAndSummary() {
        var verdict = DocumentClassificationService.parseVerdict("""
                {"categories":[{"label":"procedures","confidence":0.92}],"summary":"Une procédure."}
                """);

        assertThat(verdict.categories()).singleElement()
                .satisfies(c -> {
                    assertThat(c.label()).isEqualTo("procedures");
                    assertThat(c.confidence()).isEqualTo(0.92);
                });
        assertThat(verdict.summary()).isEqualTo("Une procédure.");
    }

    @Test
    void parseVerdict_toleratesMarkdownFenceAndSurroundingProse() {
        var verdict = DocumentClassificationService.parseVerdict("""
                Voici le résultat de mon analyse :
                ```json
                {"categories":[{"label":"securite","confidence":0.8}],"summary":"Consignes."}
                ```
                J'espère que cela convient.
                """);

        assertThat(verdict.categories()).extracting("label").containsExactly("securite");
    }

    @Test
    void parseVerdict_bareStringArray_defaultsConfidenceToOne() {
        // Forme dégradée fréquente sur les petits modèles : aucune confiance n'est donnée,
        // on ne peut donc pas filtrer sur ce critère — la catégorie est prise telle quelle.
        var verdict = DocumentClassificationService.parseVerdict(
                "{\"categories\":[\"procedures\",\"securite\"]}");

        assertThat(verdict.categories()).extracting("confidence").containsExactly(1.0, 1.0);
        assertThat(verdict.summary()).isNull();
    }

    @Test
    void parseVerdict_acceptsCategoryKeyAsLabelAlias() {
        var verdict = DocumentClassificationService.parseVerdict(
                "{\"categories\":[{\"category\":\"maintenance\",\"confidence\":0.7}]}");

        assertThat(verdict.categories()).extracting("label").containsExactly("maintenance");
    }

    @Test
    void parseVerdict_blankOrNonJsonResponse_throws() {
        assertThatThrownBy(() -> DocumentClassificationService.parseVerdict("  "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DocumentClassificationService.parseVerdict("Je ne sais pas."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sans objet JSON");
    }

    @Test
    void parseVerdict_malformedJson_throwsWithPreview() {
        assertThatThrownBy(() -> DocumentClassificationService.parseVerdict("{\"categories\": [,,]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON invalide");
    }

    @Test
    void extractJsonObject_stopsAtBalancedBrace_notAtLastBraceOfTrailingProse() {
        // Un lastIndexOf('}') naïf avalerait la phrase qui suit et casserait le parsing.
        String extracted = DocumentClassificationService.extractJsonObject(
                "{\"a\":{\"b\":1}} puis du texte avec une accolade } isolée");

        assertThat(extracted).isEqualTo("{\"a\":{\"b\":1}}");
    }

    @Test
    void extractJsonObject_ignoresBracesInsideStrings() {
        String extracted = DocumentClassificationService.extractJsonObject(
                "{\"summary\":\"accolade } dans le texte\"}");

        assertThat(extracted).isEqualTo("{\"summary\":\"accolade } dans le texte\"}");
    }

    // ── Modèles « reasoning » (le chat par défaut en est un) ─────────────────

    @Test
    void parseVerdict_ignoresTheReasoningBlockAndReadsTheRealAnswer() {
        // Phi-4-mini-reasoning expose sa réflexion avant de répondre. Le préambule contient
        // ici un objet JSON parfaitement équilibré — mais ce n'est pas le verdict.
        var verdict = DocumentClassificationService.parseVerdict("""
                <think>
                Le document parle d'interventions. Le format attendu est
                {"categories":[{"label":"exemple","confidence":0.0}],"summary":"..."}
                donc je vais répondre ainsi.
                </think>
                {"categories":[{"label":"procedures","confidence":0.88}],"summary":"Une procédure."}
                """);

        assertThat(verdict.categories()).extracting("label").containsExactly("procedures");
        assertThat(verdict.summary()).isEqualTo("Une procédure.");
    }

    @Test
    void parseVerdict_picksTheObjectCarryingCategories_notMerelyTheFirstOne() {
        // Sans bloc <think>, un modèle bavard cite quand même le format avant de conclure.
        var verdict = DocumentClassificationService.parseVerdict("""
                Rappel du format : {"format":"objet JSON","exemple":true}
                Ma réponse : {"categories":[{"label":"securite","confidence":0.7}]}
                """);

        assertThat(verdict.categories()).extracting("label").containsExactly("securite");
    }

    @Test
    void stripReasoning_dropsAnUnclosedBlock_whenGenerationWasCutOff() {
        // Bloc jamais refermé = génération coupée avant la réponse : rien d'exploitable.
        String cleaned = DocumentClassificationService.stripReasoning(
                "<think>Je réfléchis encore et la limite de tokens arrive");

        assertThat(cleaned).isEmpty();
        assertThatThrownBy(() -> DocumentClassificationService.parseVerdict(
                "<think>Je réfléchis encore et la limite de tokens arrive"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stripReasoning_handlesThinkingTagVariantsCaseInsensitively() {
        assertThat(DocumentClassificationService.stripReasoning("<Thinking>bla</Thinking>ok")).isEqualTo("ok");
        assertThat(DocumentClassificationService.stripReasoning("<reasoning>bla</reasoning>ok")).isEqualTo("ok");
        assertThat(DocumentClassificationService.stripReasoning("sans balise")).isEqualTo("sans balise");
    }

    @Test
    void jsonObjectCandidates_enumeratesEveryTopLevelObject() {
        var candidates = DocumentClassificationService.jsonObjectCandidates(
                "un {\"a\":1} deux {\"b\":{\"c\":2}} fin");

        assertThat(candidates).containsExactly("{\"a\":1}", "{\"b\":{\"c\":2}}");
    }

    // ── Politique de taxonomie ───────────────────────────────────────────────

    @Test
    void retain_matchesTaxonomyIgnoringCaseAndAccents() {
        var retained = defaultService().retain(List.of(
                new DocumentClassificationService.ScoredCategory("Procédures", 0.9)));

        assertThat(retained).extracting("label").containsExactly("procedures");
    }

    @Test
    void retain_closedTaxonomy_dropsUnknownLabels() {
        var retained = defaultService().retain(List.of(
                new DocumentClassificationService.ScoredCategory("cuisine-moleculaire", 0.99),
                new DocumentClassificationService.ScoredCategory("securite", 0.8)));

        assertThat(retained).extracting("label").containsExactly("securite");
    }

    @Test
    void retain_openTaxonomy_keepsNormalizedUnknownLabels() {
        var retained = service(config(null, true, null, null)).retain(List.of(
                new DocumentClassificationService.ScoredCategory("Génie Civil", 0.9)));

        assertThat(retained).extracting("label").containsExactly("genie-civil");
    }

    @Test
    void retain_openTaxonomy_rejectsOverlongFreeLabels() {
        // Un modèle bavard renvoie parfois une phrase entière en guise d'étiquette.
        var retained = service(config(null, true, null, null)).retain(List.of(
                new DocumentClassificationService.ScoredCategory(
                        "ce document décrit la procédure complète d'intervention sur site", 0.9)));

        assertThat(retained).isEmpty();
    }

    @Test
    void retain_dropsCategoriesBelowMinConfidence() {
        var retained = service(config(null, null, null, 0.7)).retain(List.of(
                new DocumentClassificationService.ScoredCategory("procedures", 0.9),
                new DocumentClassificationService.ScoredCategory("securite", 0.4)));

        assertThat(retained).extracting("label").containsExactly("procedures");
    }

    @Test
    void retain_sortsByConfidenceAndCapsToMaxCategories() {
        var retained = service(config(null, null, 2, 0.0)).retain(List.of(
                new DocumentClassificationService.ScoredCategory("securite", 0.5),
                new DocumentClassificationService.ScoredCategory("procedures", 0.95),
                new DocumentClassificationService.ScoredCategory("maintenance", 0.8)));

        assertThat(retained).extracting("label").containsExactly("procedures", "maintenance");
    }

    @Test
    void retain_deduplicatesVariantsKeepingBestConfidence() {
        var retained = defaultService().retain(List.of(
                new DocumentClassificationService.ScoredCategory("procedures", 0.6),
                new DocumentClassificationService.ScoredCategory("Procédures", 0.9)));

        assertThat(retained).singleElement()
                .satisfies(c -> assertThat(c.confidence()).isEqualTo(0.9));
    }

    @Test
    void retain_clampsOutOfRangeConfidence() {
        var retained = service(config(null, null, null, 0.0)).retain(List.of(
                new DocumentClassificationService.ScoredCategory("procedures", 4.2)));

        assertThat(retained).singleElement()
                .satisfies(c -> assertThat(c.confidence()).isEqualTo(1.0));
    }

    // ── Échantillonnage de l'extrait ─────────────────────────────────────────

    @Test
    void joinSampled_underLimit_keepsEveryChunk() {
        String excerpt = DocumentClassificationService.joinSampled(
                List.of("alpha", "beta", "gamma"), 8, 6000);

        assertThat(excerpt).contains("alpha").contains("beta").contains("gamma");
    }

    @Test
    void joinSampled_spansWholeDocument_includingLastChunk() {
        // L'échantillonnage doit couvrir le document de bout en bout : prendre les N
        // premiers chunks ne caractériserait que la page de garde.
        List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) chunks.add("chunk-" + i);

        String excerpt = DocumentClassificationService.joinSampled(chunks, 4, 6000);

        assertThat(excerpt).contains("chunk-0").contains("chunk-99");
    }

    @Test
    void joinSampled_truncatesLongChunksSoLaterOnesSurvive() {
        String huge = "x".repeat(5000);
        String excerpt = DocumentClassificationService.joinSampled(
                List.of(huge, "marqueur-final"), 8, 1000);

        assertThat(excerpt).contains("marqueur-final");
        assertThat(excerpt.length()).isLessThanOrEqualTo(1000);
    }

    // ── Extraction depuis ChromaDB ───────────────────────────────────────────

    @Test
    void buildExcerpt_fallsBackToSourceFileWhenNoSha256Chunk() {
        // Chunks historiques indexés avant l'ajout de la métadonnée sha256.
        when(chroma.getDocumentTextsByMetadata("col-id", "sha256", "abc", 1000))
                .thenReturn(List.of());
        when(chroma.getDocumentTextsByMetadata("col-id", "sourceFile", "consignes.pdf", 1000))
                .thenReturn(List.of("contenu historique"));

        String excerpt = defaultService().buildExcerpt(doc("abc"));

        assertThat(excerpt).isEqualTo("contenu historique");
    }

    @Test
    void buildExcerpt_chromaUnavailable_returnsEmptyRatherThanPropagating() {
        when(chroma.resolveCollectionIdUnchecked(anyString()))
                .thenThrow(new RuntimeException("ChromaDB down"));

        assertThat(defaultService().buildExcerpt(doc("abc"))).isEmpty();
    }

    // ── classify — bout en bout (dépendances mockées) ────────────────────────

    @Test
    void classify_persistsCategoriesScoresAndSummary() {
        when(fileRepo.findById("abc")).thenReturn(Optional.of(doc("abc")));
        when(chroma.getDocumentTextsByMetadata(eq("col-id"), eq("sha256"), eq("abc"), anyInt()))
                .thenReturn(List.of("Procédure d'intervention haute tension."));
        when(chat.chatJson(anyString(), anyString(), anyFloat(), anyFloat())).thenReturn(
                "{\"categories\":[{\"label\":\"procedures\",\"confidence\":0.9}],"
                + "\"summary\":\"Procédure haute tension.\"}");

        var result = defaultService().classify("abc", "user", false);

        assertThat(result.categories()).containsExactly("procedures");
        assertThat(result.scores()).containsEntry("procedures", 0.9);
        assertThat(result.summary()).isEqualTo("Procédure haute tension.");
        assertThat(result.model()).isEqualTo("phi-4-mini");
        assertThat(result.reused()).isFalse();

        verify(gedService).applyClassification(eq("abc"), eq(List.of("procedures")),
                contains("procedures"), eq("Procédure haute tension."), eq("phi-4-mini"), eq("user"));
    }

    @Test
    void classify_alreadyClassifiedWithoutForce_reusesVerdictAndSkipsLlm() {
        IngestedFileEntity classified = doc("abc");
        classified.setCategories(List.of("securite"));
        classified.setCategoryScores("{\"securite\":0.8}");
        classified.setClassifiedAt(Instant.now());
        classified.setClassifierModel("phi-4-mini");
        when(fileRepo.findById("abc")).thenReturn(Optional.of(classified));

        var result = defaultService().classify("abc", "user", false);

        assertThat(result.reused()).isTrue();
        assertThat(result.categories()).containsExactly("securite");
        assertThat(result.scores()).containsEntry("securite", 0.8);
        verifyNoInteractions(chat);
        verify(gedService, never()).applyClassification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void classify_alreadyClassifiedWithForce_callsLlmAgain() {
        IngestedFileEntity classified = doc("abc");
        classified.setCategories(List.of("securite"));
        classified.setClassifiedAt(Instant.now());
        when(fileRepo.findById("abc")).thenReturn(Optional.of(classified));
        when(chroma.getDocumentTextsByMetadata(eq("col-id"), eq("sha256"), eq("abc"), anyInt()))
                .thenReturn(List.of("texte"));
        when(chat.chatJson(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("{\"categories\":[{\"label\":\"maintenance\",\"confidence\":0.9}]}");

        var result = defaultService().classify("abc", "user", true);

        assertThat(result.reused()).isFalse();
        assertThat(result.categories()).containsExactly("maintenance");
    }

    @Test
    void classify_unknownDocument_throwsNoSuchElement() {
        when(fileRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defaultService().classify("missing", "user", false))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void classify_documentWithoutIndexedChunks_throwsExplicitly() {
        when(fileRepo.findById("abc")).thenReturn(Optional.of(doc("abc")));
        when(chroma.getDocumentTextsByMetadata(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> defaultService().classify("abc", "user", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Aucun contenu indexé");
        verifyNoInteractions(chat);
    }

    @Test
    void classify_modelReturnsOnlyOutOfTaxonomyLabels_throwsWithoutPersisting() {
        when(fileRepo.findById("abc")).thenReturn(Optional.of(doc("abc")));
        when(chroma.getDocumentTextsByMetadata(eq("col-id"), eq("sha256"), eq("abc"), anyInt()))
                .thenReturn(List.of("texte"));
        when(chat.chatJson(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("{\"categories\":[{\"label\":\"astrologie\",\"confidence\":0.99}]}");

        assertThatThrownBy(() -> defaultService().classify("abc", "user", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aucune catégorie exploitable");
        verify(gedService, never()).applyClassification(any(), any(), any(), any(), any(), any());
    }

    @Test
    void classify_serviceDisabled_throwsBeforeTouchingTheRepository() {
        assertThatThrownBy(() -> service(config(false, null, null, null))
                .classify("abc", "user", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("désactivée");
        verifyNoInteractions(fileRepo, chat);
    }

    @Test
    void classify_usesConstrainedJsonDecoding_notFreeGeneration() {
        // Le format n'est pas seulement demandé dans le prompt : il est imposé au décodeur.
        // C'est ce qui rend structurellement impossibles les préambules en prose et les
        // blocs de réflexion qui faisaient échouer la classification.
        when(fileRepo.findById("abc")).thenReturn(Optional.of(doc("abc")));
        when(chroma.getDocumentTextsByMetadata(eq("col-id"), eq("sha256"), eq("abc"), anyInt()))
                .thenReturn(List.of("texte"));
        when(chat.chatJson(anyString(), anyString(), anyFloat(), anyFloat()))
                .thenReturn("{\"categories\":[{\"label\":\"procedures\",\"confidence\":0.9}]}");

        defaultService().classify("abc", "user", false);

        verify(chat).chatJson(anyString(), anyString(), anyFloat(), anyFloat());
        verify(chat, never()).chat(anyString(), anyString(), anyFloat(), anyFloat());
        verify(chat, never()).chat(anyString(), anyString());
    }

    // ── Déclenchement automatique ────────────────────────────────────────────

    @Test
    void triggerAutoClassification_disabledByDefault_doesNothing() {
        defaultService().triggerAutoClassification("abc", false);

        verifyNoInteractions(fileRepo, chat);
    }

    @Test
    void classifyAfterIngestion_swallowsFailuresSoIngestionStaysSuccessful() {
        var svc = service(new SpectraProperties.ClassificationProperties(
                null, true, null, null, null, null, null, null, null));
        when(fileRepo.findById("abc")).thenThrow(new RuntimeException("DB down"));

        // Ne doit pas propager : une ingestion réussie ne devient pas un échec parce que
        // l'étiquetage a échoué.
        svc.classifyAfterIngestion("abc", false);
    }

    // ── Prompt ───────────────────────────────────────────────────────────────

    @Test
    void buildSystemPrompt_listsTaxonomyAndNumbersRulesContiguously() {
        String prompt = defaultService().buildSystemPrompt();

        // La règle 2 est injectée par interpolation entre les règles 1 et 3 : un saut de
        // ligne manquant collerait « …ignorée.3. Associe… » dans le prompt.
        assertThat(prompt).contains("1. Attribue au maximum 3 catégories")
                          .contains("2. N'utilise QUE des catégories")
                          .contains("\n3. Associe à chaque catégorie une confiance")
                          .contains("- procedures")
                          .contains("- reglementation");
        assertThat(prompt).doesNotContain("ignorée.3.");
    }

    @Test
    void buildSystemPrompt_openTaxonomy_allowsFreeCategories() {
        String prompt = service(config(null, true, null, null)).buildSystemPrompt();

        assertThat(prompt).contains("tu peux proposer une catégorie de ton choix")
                          .doesNotContain("N'utilise QUE");
        assertThat(prompt).contains("\n3. Associe à chaque catégorie une confiance");
    }

    @Test
    void buildUserMessage_carriesFileNameFormatAndExcerpt() {
        String message = defaultService().buildUserMessage(doc("abc"), "corps du document");

        assertThat(message).contains("consignes.pdf").contains("PDF")
                           .contains("corps du document")
                           .contains("=== FIN DES EXTRAITS ===");
    }

    // ── Configuration exposée ────────────────────────────────────────────────

    @Test
    void describeConfiguration_reportsTaxonomyAndActiveModel() {
        Map<String, Object> cfg = defaultService().describeConfiguration();

        assertThat(cfg).containsEntry("enabled", true)
                       .containsEntry("autoClassify", false)
                       .containsEntry("openTaxonomy", false)
                       .containsEntry("maxCategories", 3)
                       .containsEntry("model", "phi-4-mini");
        assertThat(cfg.get("taxonomy").toString()).contains("procedures", "reglementation");
    }

    @Test
    void parseScores_corruptedColumn_returnsEmptyMapRatherThanFailing() {
        assertThat(DocumentClassificationService.parseScores("{not json")).isEmpty();
        assertThat(DocumentClassificationService.parseScores(null)).isEmpty();
        assertThat(DocumentClassificationService.parseScores("{\"a\":0.5}"))
                .containsEntry("a", 0.5);
    }
}
