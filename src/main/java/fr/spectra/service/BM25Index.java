package fr.spectra.service;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * In-memory BM25Okapi index for a single collection.
 * Thread-safe via ReentrantReadWriteLock.
 *
 * BM25 formula: score(d,q) = Σ IDF(t) × tf(t,d)×(k1+1) / (tf(t,d) + k1×(1−b+b×|d|/avgdl))
 * IDF(t) = ln((N − df(t) + 0.5) / (df(t) + 0.5) + 1)
 * k1=1.5, b=0.75 (standard Okapi defaults)
 */
public class BM25Index {

    private static final float K1 = 1.5f;
    private static final float B  = 0.75f;
    private static final int   MIN_TOKEN_LEN = 2;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // docId → token frequency map
    private final Map<String, Map<String, Integer>> docTermFreq = new HashMap<>();
    // docId → doc length (token count)
    private final Map<String, Integer> docLengths = new HashMap<>();
    // docId → raw text (for retrieval)
    private final Map<String, String> docTexts = new HashMap<>();
    // docId → sourceFile
    private final Map<String, String> docSources = new HashMap<>();
    // token → set of docIds (inverted index for df)
    private final Map<String, Set<String>> invertedIndex = new HashMap<>();
    // running total doc length (for avgdl)
    private long totalDocLength = 0L;

    public record ScoredDoc(String id, String text, String sourceFile, float score) {}

    /** Add or re-index a document. */
    public void add(String id, String text, String sourceFile) {
        Map<String, Integer> termFreq = buildTermFreq(text);
        int docLen = termFreq.values().stream().mapToInt(i -> i).sum();

        lock.writeLock().lock();
        try {
            if (docTermFreq.containsKey(id)) {
                removeById(id);
            }
            docTermFreq.put(id, termFreq);
            docLengths.put(id, docLen);
            docTexts.put(id, text);
            docSources.put(id, sourceFile);
            totalDocLength += docLen;
            for (String token : termFreq.keySet()) {
                invertedIndex.computeIfAbsent(token, k -> new HashSet<>()).add(id);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Remove all documents belonging to sourceFile. */
    public void removeBySource(String sourceFile) {
        lock.writeLock().lock();
        try {
            List<String> toRemove = docSources.entrySet().stream()
                    .filter(e -> sourceFile.equals(e.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            toRemove.forEach(this::removeById);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Score all documents against a query, return top-N sorted by score desc. */
    public List<ScoredDoc> search(String queryText, int topN) {
        Map<String, Integer> queryTermFreq = buildTermFreq(queryText);
        if (queryTermFreq.isEmpty()) return List.of();

        lock.readLock().lock();
        try {
            int n = docTermFreq.size();
            if (n == 0) return List.of();
            float avgdl = (float) totalDocLength / n;

            Map<String, Float> scores = new HashMap<>();
            for (Map.Entry<String, Integer> queryEntry : queryTermFreq.entrySet()) {
                String token = queryEntry.getKey();
                Set<String> postings = invertedIndex.get(token);
                if (postings == null || postings.isEmpty()) continue;

                int df = postings.size();
                float idf = (float) Math.log((n - df + 0.5) / (df + 0.5) + 1.0);

                for (String docId : postings) {
                    Map<String, Integer> tf = docTermFreq.get(docId);
                    if (tf == null) continue;
                    float tfVal = tf.getOrDefault(token, 0);
                    int dl = docLengths.getOrDefault(docId, 1);
                    float tfNorm = tfVal * (K1 + 1) / (tfVal + K1 * (1 - B + B * dl / avgdl));
                    scores.merge(docId, idf * tfNorm, Float::sum);
                }
            }

            return scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Float>comparingByValue().reversed())
                    .limit(topN)
                    .map(e -> new ScoredDoc(
                            e.getKey(),
                            docTexts.getOrDefault(e.getKey(), ""),
                            docSources.getOrDefault(e.getKey(), "inconnu"),
                            e.getValue()))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try { return docTermFreq.size(); }
        finally { lock.readLock().unlock(); }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /** Must be called with write lock held. */
    private void removeById(String id) {
        Map<String, Integer> termFreq = docTermFreq.remove(id);
        if (termFreq != null) {
            int docLen = docLengths.remove(id);
            totalDocLength -= docLen;
            for (String token : termFreq.keySet()) {
                Set<String> postings = invertedIndex.get(token);
                if (postings != null) {
                    postings.remove(id);
                    if (postings.isEmpty()) invertedIndex.remove(token);
                }
            }
        }
        docTexts.remove(id);
        docSources.remove(id);
    }

    /** Tokenize + compute term frequencies.
     *
     * <p>Deux passes pour les termes composés avec tiret :
     * <ol>
     *   <li>Le terme complet est conservé ({@code porte-à-faux}, {@code CO2-NF}) afin que
     *       les requêtes exactes obtiennent un score BM25 élevé.</li>
     *   <li>Ses parties sont ajoutées séparément pour que les recherches partielles matchent.</li>
     * </ol>
     */
    private static Map<String, Integer> buildTermFreq(String text) {
        if (text == null || text.isBlank()) return Map.of();
        Map<String, Integer> freq = new HashMap<>();
        // Split sur les espaces uniquement pour conserver les tirets dans les tokens bruts
        for (String raw : text.toLowerCase().split("\\s+")) {
            // Nettoyer les caractères non significatifs en conservant le tiret à l'intérieur
            String cleaned = raw.replaceAll("[^a-zA-Z0-9àâäéèêëîïôùûüçœæ\\-]", "")
                                 .replaceAll("^-+|-+$", ""); // supprimer tirets en début/fin
            if (cleaned.length() < MIN_TOKEN_LEN) continue;
            freq.merge(cleaned, 1, Integer::sum);   // terme complet (ex: "porte-à-faux")
            if (cleaned.contains("-")) {
                for (String part : cleaned.split("-")) {
                    if (part.length() >= MIN_TOKEN_LEN) {
                        freq.merge(part, 1, Integer::sum);  // parties (ex: "porte", "faux")
                    }
                }
            }
        }
        return freq;
    }
}
