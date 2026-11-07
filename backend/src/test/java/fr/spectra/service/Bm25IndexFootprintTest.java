package fr.spectra.service;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mesure l'empreinte mémoire de l'index BM25 par passage, pour dimensionner le tas avant un essai
 * sur le corpus complet (≈ 500 000 documents).
 *
 * <p>Le vocabulaire suit une loi de Zipf (quelques mots très fréquents, une longue traîne), comme un
 * texte réel. La mesure est le tas retenu après ramasse-miettes ; elle varie de quelques pourcents
 * d'une exécution à l'autre, d'où une borne large plutôt qu'une égalité.
 *
 * <p>Repère mesuré sur un vrai index (26 572 passages issus de 7 402 documents EnterpriseRAG-Bench,
 * fichier sérialisé de 127 Mo) : 655 Mo de tas retenu, soit ≈ 26 Ko par passage. Le jeu synthétique ci-dessous
 * donne le même ordre de grandeur (≈ 22 Ko pour 200 jetons), ce qui le rend utilisable comme garde-fou.
 */
class Bm25IndexFootprintTest {

    private static final int CHUNKS = 20_000;
    private static final int TOKENS_PER_CHUNK = 200;
    private static final int VOCABULARY = 60_000;

    private static String text(Random random, double[] cumulative) {
        StringBuilder sb = new StringBuilder(TOKENS_PER_CHUNK * 8);
        for (int i = 0; i < TOKENS_PER_CHUNK; i++) {
            double u = random.nextDouble() * cumulative[cumulative.length - 1];
            int lo = 0, hi = cumulative.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulative[mid] < u) lo = mid + 1; else hi = mid;
            }
            sb.append("terme").append(lo).append(' ');
        }
        return sb.toString();
    }

    private static long usedHeap() {
        for (int i = 0; i < 4; i++) System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    void reportsRetainedBytesPerChunk() {
        double[] cumulative = new double[VOCABULARY];
        double sum = 0;
        for (int i = 0; i < VOCABULARY; i++) { sum += 1.0 / (i + 1); cumulative[i] = sum; }
        Random random = new Random(42);

        long before = usedHeap();
        BM25Index index = new BM25Index();
        for (int i = 0; i < CHUNKS; i++) {
            index.add("chunk-" + i, text(random, cumulative), "doc-" + (i / 4) + ".txt", "sha" + (i / 4));
        }
        long after = usedHeap();

        long perChunk = (after - before) / CHUNKS;
        System.out.printf("BM25 : %d passages de %d jetons → %d octets retenus par passage (%.1f Mo au total)%n",
                CHUNKS, TOKENS_PER_CHUNK, perChunk, (after - before) / 1_048_576.0);
        assertThat(index.size()).isEqualTo(CHUNKS);
        assertThat(perChunk).as("l'empreinte par passage ne doit pas exploser").isLessThan(60_000);
    }
}
