package fr.spectra.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires de BM25Index — algorithme BM25Okapi in-memory.
 */
class BM25IndexTest {

    private BM25Index index;

    @BeforeEach
    void setUp() {
        index = new BM25Index();
    }

    // ── add / size ────────────────────────────────────────────────────────────

    @Test
    void add_singleDoc_sizeIsOne() {
        index.add("d1", "hello world", "src.txt");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void add_multipleDistinctDocs_sizeMatches() {
        index.add("d1", "premier document", "a.txt");
        index.add("d2", "deuxième document", "b.txt");
        index.add("d3", "troisième document", "c.txt");
        assertThat(index.size()).isEqualTo(3);
    }

    @Test
    void add_sameIdTwice_doesNotDuplicate() {
        index.add("d1", "version 1", "src.txt");
        index.add("d1", "version 2", "src.txt");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void add_sameIdUpdatesText() {
        index.add("d1", "ancien texte unique", "src.txt");
        index.add("d1", "nouveau texte complètement différent", "src.txt");

        List<BM25Index.ScoredDoc> results = index.search("nouveau", 5);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).text()).isEqualTo("nouveau texte complètement différent");
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_emptyIndex_returnsEmptyList() {
        assertThat(index.search("query", 5)).isEmpty();
    }

    @Test
    void search_emptyQuery_returnsEmptyList() {
        index.add("d1", "some content", "src.txt");
        assertThat(index.search("", 5)).isEmpty();
    }

    @Test
    void search_blankQuery_returnsEmptyList() {
        index.add("d1", "some content", "src.txt");
        assertThat(index.search("   ", 5)).isEmpty();
    }

    @Test
    void search_exactMatch_returnsDoc() {
        index.add("d1", "spectra est une plateforme rag", "doc.txt");
        List<BM25Index.ScoredDoc> results = index.search("spectra", 5);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("d1");
    }

    @Test
    void search_mostRelevantFirst() {
        // d1 contient "chat" 3 fois, d2 une seule fois
        index.add("d1", "chat chat chat domestique animal", "a.txt");
        index.add("d2", "chat sauvage lion tigre jaguar", "b.txt");
        index.add("d3", "chien caniche berger labrador", "c.txt");

        List<BM25Index.ScoredDoc> results = index.search("chat", 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).id()).isEqualTo("d1");
    }

    @Test
    void search_topNLimitsResults() {
        for (int i = 0; i < 10; i++) {
            index.add("d" + i, "document contenu texte " + i, "f" + i + ".txt");
        }
        List<BM25Index.ScoredDoc> results = index.search("document", 3);
        assertThat(results).hasSize(3);
    }

    @Test
    void search_unknownToken_returnsEmptyList() {
        index.add("d1", "bonjour monde", "src.txt");
        assertThat(index.search("xyzzy", 5)).isEmpty();
    }

    @Test
    void search_scoreIsPositive() {
        index.add("d1", "indexation vectorielle", "src.txt");
        List<BM25Index.ScoredDoc> results = index.search("indexation", 5);
        assertThat(results.get(0).score()).isGreaterThan(0f);
    }

    @Test
    void search_sourceFilePreserved() {
        index.add("d1", "contenu du document", "mon_fichier.txt");
        List<BM25Index.ScoredDoc> results = index.search("contenu", 5);
        assertThat(results.get(0).sourceFile()).isEqualTo("mon_fichier.txt");
    }

    @Test
    void search_textPreservedInResult() {
        String text = "le texte original complet est conservé";
        index.add("d1", text, "src.txt");
        List<BM25Index.ScoredDoc> results = index.search("original", 5);
        assertThat(results.get(0).text()).isEqualTo(text);
    }

    // ── Tokenisation et tirets ────────────────────────────────────────────────

    @Test
    void search_hyphenatedTerm_matchesFullTerm() {
        index.add("d1", "porte-à-faux technique de construction", "src.txt");
        List<BM25Index.ScoredDoc> results = index.search("porte-à-faux", 5);
        assertThat(results).isNotEmpty();
    }

    @Test
    void search_hyphenatedTerm_matchesPartialTerm() {
        index.add("d1", "porte-à-faux technique de construction", "src.txt");
        List<BM25Index.ScoredDoc> results = index.search("porte", 5);
        assertThat(results).isNotEmpty();
    }

    @Test
    void search_shortTokensIgnored() {
        // Tokens de 1 caractère doivent être ignorés
        index.add("d1", "a b c hello world", "src.txt");
        assertThat(index.search("hello", 5)).hasSize(1);
        // Même si "a" est dans le doc, la recherche sur un token court retourne vide
        assertThat(index.search("a", 5)).isEmpty();
    }

    // ── removeBySource ────────────────────────────────────────────────────────

    @Test
    void removeBySource_removesAllDocsOfSource() {
        index.add("d1", "doc un source A", "sourceA.txt");
        index.add("d2", "doc deux source A", "sourceA.txt");
        index.add("d3", "doc trois source B", "sourceB.txt");

        index.removeBySource("sourceA.txt");

        assertThat(index.size()).isEqualTo(1);
        // Les docs de sourceA ont été supprimés : "source" + "un"/"deux" ne matchent plus
        assertThat(index.search("deux", 5)).isEmpty();
        // Le doc de sourceB reste : il contient "trois"
        assertThat(index.search("trois", 5)).isNotEmpty();
    }

    @Test
    void removeBySource_unknownSource_noEffect() {
        index.add("d1", "contenu existant", "src.txt");
        index.removeBySource("inconnu.txt");
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void removeBySource_afterRemoval_searchReturnsEmpty() {
        index.add("d1", "intelligence artificielle machine learning", "ia.txt");
        index.removeBySource("ia.txt");
        assertThat(index.search("intelligence", 5)).isEmpty();
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    void concurrentAdds_noRaceCondition() throws InterruptedException {
        int threads = 8;
        int docsPerThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    for (int i = 0; i < docsPerThread; i++) {
                        String id = "t" + threadId + "_d" + i;
                        index.add(id, "contenu thread " + threadId + " doc " + i, "src.txt");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();

        assertThat(index.size()).isEqualTo(threads * docsPerThread);
    }
}
