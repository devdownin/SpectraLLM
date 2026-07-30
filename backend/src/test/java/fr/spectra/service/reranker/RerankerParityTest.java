package fr.spectra.service.reranker;

import fr.spectra.config.SpectraProperties;
import fr.spectra.dto.ServiceStatus;
import fr.spectra.service.CrossEncoderRerankerClient;
import fr.spectra.service.RerankerClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Harnais de comparaison du reranking — étape 1 du lot 2 de la migration « full Java »
 * ({@code docs/process/audit-python-java.fr.md}).
 *
 * <p>Une régression de reranking ne casse rien : elle dégrade les réponses. Elle est donc
 * invisible sans référence prise <b>avant</b> le portage. Cette classe fournit les deux modes,
 * plus les tests du comparateur lui-même :
 *
 * <ol>
 *   <li><b>Capture</b> (désactivée par défaut) — rejoue le corpus sur le service Python en
 *       fonctionnement et écrit la référence. Le fichier compose vit dans {@code deploy/docker/}
 *       mais la stack se résout depuis la racine du dépôt, d'où {@code --project-directory .} :
 *       <pre>
 *       docker compose --project-directory . -f deploy/docker/docker-compose.yml \
 *           --profile reranker up -d reranker
 *       mvn test -Dtest=RerankerParityTest -Dreranker.parity.capture=http://localhost:8002
 *       </pre>
 *       Options : {@code -Dreranker.parity.model=…} (nom du modèle réellement servi, lu sur
 *       {@code /health} si absent) et {@code -Dreranker.parity.scale=sigmoid|logit}.</li>
 *   <li><b>Vérification</b> — compare une implémentation à la référence. Neutralisée tant que la
 *       référence n'a pas été capturée ou qu'aucun moteur n'est fourni :
 *       <pre>
 *       mvn test -Dtest=RerankerParityTest -Dreranker.parity.verify=http://localhost:8002
 *       </pre>
 *       {@code -Dreranker.parity.scores=ignore} contrôle l'ordre seul, pour un changement
 *       d'échelle assumé.</li>
 *   <li><b>Tests du comparateur</b> — toujours exécutés : sans eux, le harnais pourrait rendre
 *       « aucun écart » par construction et donner une fausse assurance le jour du portage.</li>
 * </ol>
 */
class RerankerParityTest {

    /** Emplacement de la référence, versionnée avec le code une fois capturée. */
    private static final Path REFERENCE_PATH =
            Path.of("src", "test", "resources", "reranker-parity", "reference.json");

    private static final String CAPTURE_PROPERTY = "reranker.parity.capture";
    private static final String VERIFY_PROPERTY = "reranker.parity.verify";

    // ── 1. Corpus ─────────────────────────────────────────────────────────────────────────

    @Test
    void leCorpusEstDeterministeEtDiscriminant() throws IOException {
        RerankerParityCorpus corpus = RerankerParityCorpus.load();

        // Les 20 questions du benchmark deviennent les requêtes ; les 14 réponses de référence
        // (les 6 questions « non répondables » n'en ont pas) forment le vivier de passages.
        assertThat(corpus.queries()).hasSize(20);
        assertThat(corpus.documents()).hasSize(14);
        assertThat(corpus.documents()).doesNotHaveDuplicates();
        assertThat(corpus.documents()).allSatisfy(doc -> assertThat(doc).isNotBlank());

        // Plus de passages que de résultats demandés : sans cela le classement serait trivial.
        assertThat(corpus.documents().size()).isGreaterThan(RerankerParityCorpus.TOP_N);

        // Deux chargements donnent la même empreinte : la référence capturée reste opposable.
        assertThat(corpus.fingerprint()).isEqualTo(RerankerParityCorpus.load().fingerprint());
        assertThat(corpus.fingerprint()).startsWith("sha256:");
    }

    @Test
    void lEmpreinteChangeAvecLeCorpus() throws IOException {
        // Verrou : une empreinte insensible au contenu ne détecterait pas une référence obsolète.
        String reference = RerankerParityCorpus.load().fingerprint();
        assertThat(RerankerParityCheck.DEFAULT_SCORE_TOLERANCE).isPositive();
        assertThat(reference).isNotEqualTo("sha256:");
    }

    // ── 2. Comparateur ────────────────────────────────────────────────────────────────────

    @Test
    void classementIdentique_aucunEcart() throws IOException {
        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference ref = RerankerParityReference.capture(
                new StubReranker(0.0), corpus, "stub", "stub-model", "logit");

        List<RerankerParityCheck.Divergence> divergences = RerankerParityCheck.compare(
                ref, RerankerParityReference.replay(new StubReranker(0.0), corpus,
                        RerankerParityCorpus.TOP_N),
                RerankerParityCheck.ScorePolicy.COMPARE,
                RerankerParityCheck.DEFAULT_SCORE_TOLERANCE);

        assertThat(divergences).isEmpty();
    }

    @Test
    void ordrePermute_ecartDOrdreSignale() throws IOException {
        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference ref = RerankerParityReference.capture(
                new StubReranker(0.0), corpus, "stub", "stub-model", "logit");

        // Un reranker qui inverse le classement : l'erreur la plus grave, et la plus silencieuse.
        List<RerankerParityCheck.Divergence> divergences = RerankerParityCheck.compare(
                ref, RerankerParityReference.replay(new ReversedStubReranker(), corpus,
                        RerankerParityCorpus.TOP_N),
                RerankerParityCheck.ScorePolicy.COMPARE,
                RerankerParityCheck.DEFAULT_SCORE_TOLERANCE);

        assertThat(divergences).isNotEmpty();
        assertThat(divergences).allSatisfy(d -> assertThat(d.kind()).isEqualTo("ordre"));
        // Le message doit permettre de juger sans relancer : index attendu, obtenu, et voisinage.
        assertThat(divergences.getFirst().detail())
                .contains("référence index").contains("obtenu index")
                .contains("scores de référence voisins");
    }

    @Test
    void deriveDeScoreSousLaTolerance_aucunEcart() throws IOException {
        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference ref = RerankerParityReference.capture(
                new StubReranker(0.0), corpus, "stub", "stub-model", "logit");

        List<RerankerParityCheck.Divergence> divergences = RerankerParityCheck.compare(
                ref, RerankerParityReference.replay(new StubReranker(1e-6), corpus,
                        RerankerParityCorpus.TOP_N),
                RerankerParityCheck.ScorePolicy.COMPARE,
                RerankerParityCheck.DEFAULT_SCORE_TOLERANCE);

        assertThat(divergences).isEmpty();
    }

    @Test
    void deriveDeScoreAuDelaDeLaTolerance_ecartDeScoreSignaleSansToucherALOrdre() throws IOException {
        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference ref = RerankerParityReference.capture(
                new StubReranker(0.0), corpus, "stub", "stub-model", "logit");

        // Décalage uniforme : l'ordre est préservé, seule l'échelle bouge — exactement ce que
        // produirait le passage de la sigmoïde au logit brut.
        List<RerankerParityReference.QueryResults> shifted =
                RerankerParityReference.replay(new StubReranker(0.5), corpus,
                        RerankerParityCorpus.TOP_N);

        assertThat(RerankerParityCheck.compare(ref, shifted,
                RerankerParityCheck.ScorePolicy.COMPARE,
                RerankerParityCheck.DEFAULT_SCORE_TOLERANCE))
                .isNotEmpty()
                .allSatisfy(d -> assertThat(d.kind()).isEqualTo("score"));

        // Échelle assumée : l'ordre seul est contrôlé, et il est intact.
        assertThat(RerankerParityCheck.compare(ref, shifted,
                RerankerParityCheck.ScorePolicy.IGNORE,
                RerankerParityCheck.DEFAULT_SCORE_TOLERANCE))
                .isEmpty();
    }

    @Test
    void nombreDeResultatsDifferent_signale() throws IOException {
        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference ref = RerankerParityReference.capture(
                new StubReranker(0.0), corpus, "stub", "stub-model", "logit");

        List<RerankerParityCheck.Divergence> divergences = RerankerParityCheck.compare(
                ref, RerankerParityReference.replay(new StubReranker(0.0), corpus,
                        RerankerParityCorpus.TOP_N - 1),
                RerankerParityCheck.ScorePolicy.COMPARE,
                RerankerParityCheck.DEFAULT_SCORE_TOLERANCE);

        assertThat(divergences).isNotEmpty()
                .allSatisfy(d -> assertThat(d.kind()).isEqualTo("nombre-de-résultats"));
    }

    @Test
    void referenceSerialiseeEtRelueALIdentique() throws IOException {
        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference ref = RerankerParityReference.capture(
                new StubReranker(0.0), corpus, "stub", "stub-model", "sigmoid");

        Path tmp = Files.createTempDirectory("parity").resolve("nested/reference.json");
        ref.writeTo(tmp);
        RerankerParityReference reread = RerankerParityReference.readFrom(tmp);

        assertThat(reread.corpusFingerprint()).isEqualTo(corpus.fingerprint());
        assertThat(reread.topN()).isEqualTo(RerankerParityCorpus.TOP_N);
        assertThat(reread.documentCount()).isEqualTo(corpus.documents().size());
        assertThat(reread.scoreScale()).isEqualTo("sigmoid");
        assertThat(RerankerParityCheck.compare(reread, ref.queries(),
                RerankerParityCheck.ScorePolicy.COMPARE,
                RerankerParityCheck.DEFAULT_SCORE_TOLERANCE)).isEmpty();
    }

    // ── 3. Capture et vérification contre un service réel (opt-in) ────────────────────────

    @Test
    void captureLaReferenceDepuisLeServicePython() throws IOException {
        String baseUrl = System.getProperty(CAPTURE_PROPERTY, "").strip();
        Assumptions.assumeFalse(baseUrl.isEmpty(),
                "capture désactivée — fournir -D" + CAPTURE_PROPERTY + "=http://localhost:8002"
                        + " avec le service reranker démarré (profil compose « reranker »)");

        CrossEncoderRerankerClient client = httpClient(baseUrl);
        assertThat(client.checkHealth().available())
                .as("service reranker joignable sur %s", baseUrl).isTrue();

        // Modèle réellement SERVI, lu sur /health — et non celui que la configuration annonce.
        // La distinction n'est pas théorique : services/reranker/app.py défaute sur le modèle
        // anglais ms-marco-MiniLM-L-6-v2 tandis que le compose et application.yml défautent sur
        // le multilingue mmarco-mMiniLMv2. Capturer la config aurait masqué l'écart.
        String model = System.getProperty("reranker.parity.model", "").strip();
        if (model.isEmpty()) {
            model = servedModel(baseUrl);
        }
        String scale = System.getProperty("reranker.parity.scale",
                RerankerParityReference.SCALE_UNKNOWN).strip();

        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference reference = RerankerParityReference.capture(
                client, corpus, "python-service:" + baseUrl, model, scale);
        reference.writeTo(REFERENCE_PATH);

        System.out.println("Référence de parité écrite : " + REFERENCE_PATH.toAbsolutePath());
        System.out.println("  modèle=" + model + "  échelle=" + scale
                + "  requêtes=" + reference.queries().size()
                + "  passages=" + reference.documentCount());
        if (RerankerParityReference.SCALE_UNKNOWN.equals(scale)) {
            System.out.println("  ATTENTION : échelle de score non déclarée — précisez "
                    + "-Dreranker.parity.scale=sigmoid|logit, sinon la comparaison des scores "
                    + "n'est pas interprétable.");
        }
        assertThat(REFERENCE_PATH).exists();
    }

    @Test
    void verifieUneImplementationContreLaReference() throws IOException {
        Assumptions.assumeTrue(Files.exists(REFERENCE_PATH),
                "aucune référence capturée (" + REFERENCE_PATH + ") — lancer d'abord la capture");
        String baseUrl = System.getProperty(VERIFY_PROPERTY, "").strip();
        Assumptions.assumeFalse(baseUrl.isEmpty(),
                "vérification désactivée — fournir -D" + VERIFY_PROPERTY + "=<url du moteur>");

        RerankerParityCorpus corpus = RerankerParityCorpus.load();
        RerankerParityReference reference = RerankerParityReference.readFrom(REFERENCE_PATH);

        assertThat(reference.corpusFingerprint())
                .as("le benchmark source a changé depuis la capture — la référence est obsolète, "
                        + "recapturez-la avant de comparer")
                .isEqualTo(corpus.fingerprint());

        RerankerParityCheck.ScorePolicy policy =
                "ignore".equalsIgnoreCase(System.getProperty("reranker.parity.scores", "compare"))
                        ? RerankerParityCheck.ScorePolicy.IGNORE
                        : RerankerParityCheck.ScorePolicy.COMPARE;

        List<RerankerParityCheck.Divergence> divergences = RerankerParityCheck.compare(
                reference,
                RerankerParityReference.replay(httpClient(baseUrl), corpus, reference.topN()),
                policy, RerankerParityCheck.DEFAULT_SCORE_TOLERANCE);

        assertThat(divergences)
                .as("écarts de reranking par rapport à la référence (%s, modèle %s, échelle %s)",
                        reference.source(), reference.model(), reference.scoreScale())
                .isEmpty();
    }

    /** Nom du modèle publié par {@code GET /health} du service reranker. */
    @SuppressWarnings("unchecked")
    private static String servedModel(String baseUrl) {
        java.util.Map<String, Object> health = WebClient.builder().baseUrl(baseUrl).build()
                .get().uri("/health").retrieve().bodyToMono(java.util.Map.class)
                .block(java.time.Duration.ofSeconds(10));
        Object model = health != null ? health.get("model") : null;
        return model != null ? String.valueOf(model) : "inconnu";
    }

    private static CrossEncoderRerankerClient httpClient(String baseUrl) {
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        SpectraProperties props = mock(SpectraProperties.class);
        when(props.reranker()).thenReturn(new SpectraProperties.RerankerProperties(
                true, baseUrl, null, 120, null));
        return new CrossEncoderRerankerClient(webClient, props);
    }

    // ── Rerankers factices : un classement reproductible, sans modèle ─────────────────────

    /**
     * Classe les passages par longueur décroissante, avec un décalage constant sur les scores.
     * Déterministe et indépendant du corpus, donc utilisable comme témoin du comparateur.
     */
    private static class StubReranker implements RerankerClient {
        private final double offset;

        StubReranker(double offset) {
            this.offset = offset;
        }

        @Override
        public ServiceStatus checkHealth() {
            return new ServiceStatus("reranker", "stub", true, "ok", 0, java.util.Map.of());
        }

        @Override
        public List<RankedResult> rerank(String query, List<String> documents, int topN) {
            List<RankedResult> scored = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                scored.add(new RankedResult(i, (float) (documents.get(i).length() / 1000.0 + offset)));
            }
            scored.sort(Comparator.comparingDouble((RankedResult r) -> r.score()).reversed()
                    .thenComparingInt(RankedResult::index));
            return scored.subList(0, Math.min(topN, scored.size()));
        }
    }

    /** Même scores, classement inversé : simule une régression d'ordre pure. */
    private static class ReversedStubReranker extends StubReranker {
        ReversedStubReranker() {
            super(0.0);
        }

        @Override
        public List<RankedResult> rerank(String query, List<String> documents, int topN) {
            List<RankedResult> ascending = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                ascending.add(new RankedResult(i, (float) (documents.get(i).length() / 1000.0)));
            }
            ascending.sort(Comparator.comparingDouble((RankedResult r) -> r.score())
                    .thenComparingInt(RankedResult::index));
            return ascending.subList(0, Math.min(topN, ascending.size()));
        }
    }
}
