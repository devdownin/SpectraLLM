package fr.spectra.service;

import java.io.IOException;
import java.io.Serializable;
import java.text.Normalizer;
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
 *
 * <p><b>Champ d'en-tête.</b> Titre, fichier et section du chunk ({@link ContextualHeader#terms})
 * sont indexés dans un champ distinct du texte, avec leurs propres statistiques, et pèsent
 * {@link #HEADER_WEIGHT} dans le score. Le texte cité reste intact, et un en-tête répété sur tous
 * les chunks d'un document n'allonge pas leur texte (ce qui pénaliserait leur normalisation de
 * longueur) : une question qui nomme un document ou une section remonte ses chunks, et le texte
 * départage entre eux.</p>
 */
public class BM25Index implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final float K1 = 1.5f;
    private static final float B  = 0.75f;
    private static final int   MIN_TOKEN_LEN = 2;

    /**
     * Version de l'analyse lexicale. Un index sérialisé avec une autre version a des termes que
     * la requête ne produit plus (accents, pluriels) : il doit être reconstruit, pas servi.
     * Les index antérieurs au champ se désérialisent à 0.
     */
    static final int ANALYZER_VERSION = 3;

    /** Poids du champ d'en-tête relativement au texte : un appoint, pas un substitut. */
    static final float HEADER_WEIGHT = 0.5f;

    /**
     * Mots vides français et anglais. Leur IDF est faible, mais pas nulle : sur une question
     * courte, « de », « la », « les » pesaient autant qu'un terme rare manquant, et un chunk
     * bavard battait un chunk pertinent.
     */
    private static final Set<String> STOP_WORDS = Set.of(
            "au", "aux", "avec", "ce", "ces", "cet", "cette", "dans", "de", "des", "du", "elle", "en",
            "est", "et", "eux", "il", "ils", "je", "la", "le", "les", "leur", "leurs", "lui", "ma",
            "mais", "me", "mes", "mon", "ne", "nos", "notre", "nous", "on", "ou", "par", "pas", "pour",
            "qu", "que", "qui", "sa", "se", "ses", "son", "sont", "sur", "ta", "te", "tes", "toi",
            "ton", "tu", "un", "une", "vos", "votre", "vous", "été", "être", "ont", "était",
            "comme", "quel", "quelle", "quels", "quelles", "the", "of", "and", "to", "in", "is", "for",
            "with", "as", "by", "at", "an", "be", "are", "or", "this", "that", "it", "from");

    // Patterns précompilés : buildTermFreq est sur le chemin chaud (indexation ET recherche) ;
    // les replaceAll(String) recompilaient ces regex pour chaque token.
    /** Séparateurs de tokens : blancs + apostrophes (élisions françaises « l'énergie » → « énergie »). */
    private static final java.util.regex.Pattern TOKEN_SPLIT =
            java.util.regex.Pattern.compile("[\\s'’]+");
    /** Caractères non significatifs (tiret conservé à l'intérieur des tokens). */
    private static final java.util.regex.Pattern NON_WORD =
            java.util.regex.Pattern.compile("[^a-z0-9àâäéèêëîïôùûüçœæ\\-]");

    private transient ReadWriteLock lock = new ReentrantReadWriteLock();
    private int analyzerVersion = ANALYZER_VERSION;

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        this.lock = new ReentrantReadWriteLock();
        if (docIdentities == null) docIdentities = new HashMap<>();
        if (docHeaders == null) docHeaders = new HashMap<>();
        if (headerTermFreq == null) headerTermFreq = new HashMap<>();
        if (headerLengths == null) headerLengths = new HashMap<>();
        if (headerIndex == null) headerIndex = new HashMap<>();
    }

    /**
     * Sérialisation sous verrou de lecture : permet de persister l'index sur disque
     * PENDANT que d'autres threads indexent (la persistance différée de FtsService
     * n'est plus faite sous le verrou de la map) sans risquer une
     * ConcurrentModificationException ni un instantané incohérent.
     */
    private void writeObject(java.io.ObjectOutputStream out) throws IOException {
        lock.readLock().lock();
        try {
            out.defaultWriteObject();
        } finally {
            lock.readLock().unlock();
        }
    }

    // docId → token frequency map
    private final Map<String, Map<String, Integer>> docTermFreq = new HashMap<>();
    // docId → doc length (token count)
    private final Map<String, Integer> docLengths = new HashMap<>();
    // docId → raw text (for retrieval)
    private final Map<String, String> docTexts = new HashMap<>();
    // docId → sourceFile
    private final Map<String, String> docSources = new HashMap<>();
    // Compatible avec les anciens fichiers sérialisés : reconstruits depuis ChromaDB.
    private Map<String, String> docIdentities = new HashMap<>();
    private int identityVersion = 1;
    // token → set of docIds (inverted index for df)
    private final Map<String, Set<String>> invertedIndex = new HashMap<>();
    // running total doc length (for avgdl)
    private long totalDocLength = 0L;
    // Champ d'en-tête (absent des index sérialisés avant la version 3 de l'analyse).
    private Map<String, String> docHeaders = new HashMap<>();
    private Map<String, Map<String, Integer>> headerTermFreq = new HashMap<>();
    private Map<String, Integer> headerLengths = new HashMap<>();
    private Map<String, Set<String>> headerIndex = new HashMap<>();
    private long totalHeaderLength = 0L;

    /** Includes all fields that affect retrieval, not merely document cardinality. */
    public String corpusFingerprint() {
        lock.readLock().lock();
        try {
            CorpusFingerprint fingerprint = new CorpusFingerprint();
            docTexts.forEach((id, text) -> fingerprint.add(id, text, docSources.get(id),
                    docIdentities.get(id), docHeaders.get(id)));
            return fingerprint.value();
        } finally {
            lock.readLock().unlock();
        }
    }

    public record ScoredDoc(String id, String text, String sourceFile, float score) {}

    /** Add or re-index a document. */
    public void add(String id, String text, String sourceFile) {
        add(id, text, sourceFile, null);
    }

    public void add(String id, String text, String sourceFile, String sha256) {
        add(id, text, sourceFile, sha256, null);
    }

    /** @param header termes d'en-tête du chunk (titre, fichier, section) ; {@code null} = aucun */
    public void add(String id, String text, String sourceFile, String sha256, String header) {
        Map<String, Integer> termFreq = buildTermFreq(text);
        int docLen = termFreq.values().stream().mapToInt(i -> i).sum();
        Map<String, Integer> headerFreq = buildTermFreq(header);
        int headerLen = headerFreq.values().stream().mapToInt(i -> i).sum();

        lock.writeLock().lock();
        try {
            if (docTermFreq.containsKey(id)) {
                removeById(id);
            }
            docTermFreq.put(id, termFreq);
            docLengths.put(id, docLen);
            docTexts.put(id, text);
            docSources.put(id, sourceFile);
            if (sha256 != null) docIdentities.put(id, sha256);
            totalDocLength += docLen;
            for (String token : termFreq.keySet()) {
                invertedIndex.computeIfAbsent(token, k -> new HashSet<>()).add(id);
            }
            if (header != null) docHeaders.put(id, header);
            if (!headerFreq.isEmpty()) {
                headerTermFreq.put(id, headerFreq);
                headerLengths.put(id, headerLen);
                totalHeaderLength += headerLen;
                for (String token : headerFreq.keySet()) {
                    headerIndex.computeIfAbsent(token, k -> new HashSet<>()).add(id);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Recopie tous les documents de {@code other} dans cet index (un id déjà présent est
     * ré-indexé, l'opération est donc idempotente). Utilisé à la fin d'un rebuild pour
     * ré-appliquer les chunks indexés en direct PENDANT la reconstruction — l'ancien
     * remplacement pur ({@code indices.put}) les perdait jusqu'au rebuild suivant.
     */
    public void addAll(BM25Index other) {
        List<String[]> docs;
        other.lock.readLock().lock();
        try {
            docs = other.docTexts.entrySet().stream()
                    .map(e -> new String[]{e.getKey(), e.getValue(), other.docSources.get(e.getKey()),
                            other.docIdentities.get(e.getKey()), other.docHeaders.get(e.getKey())})
                    .toList();
        } finally {
            other.lock.readLock().unlock();
        }
        docs.forEach(d -> add(d[0], d[1], d[2], d[3], d[4]));
    }

    public boolean supportsDocumentIdentity() { return identityVersion == 1; }

    /** L'index a-t-il été construit avec l'analyse lexicale courante ? */
    public boolean usesCurrentAnalyzer() { return analyzerVersion == ANALYZER_VERSION; }

    /** Suppression par identité, indépendamment du nom affiché. */
    public void removeByDocument(String sha256) {
        lock.writeLock().lock();
        try {
            docIdentities.entrySet().stream()
                    .filter(e -> sha256.equals(e.getValue()))
                    .map(Map.Entry::getKey).toList().forEach(this::removeById);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Repli réservé aux chunks historiques sans identité ; jamais aux homonymes modernes. */
    public void removeLegacyBySource(String sourceFile) {
        lock.writeLock().lock();
        try {
            docSources.entrySet().stream()
                    .filter(e -> sourceFile.equals(e.getValue()) && !docIdentities.containsKey(e.getKey()))
                    .map(Map.Entry::getKey).toList().forEach(this::removeById);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Remove obsolete generations of a stream source; null retainedIdentity deletes all. */
    public void pruneStreamVersions(String sourceFile, String retainedIdentity) {
        lock.writeLock().lock();
        try {
            docSources.entrySet().stream()
                    .filter(e -> sourceFile.equals(e.getValue()) && (retainedIdentity == null
                            || !retainedIdentity.equals(docIdentities.get(e.getKey()))))
                    .map(Map.Entry::getKey).toList().forEach(this::removeById);
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
        return search(queryText, topN, null);
    }

    /**
     * Comme {@link #search(String, int)}, restreint aux documents acceptés par {@code accept}
     * (empreinte sha256 nullable, source) — filtre appliqué avant la coupe top-N.
     */
    public List<ScoredDoc> search(String queryText, int topN,
                                  java.util.function.BiPredicate<String, String> accept) {
        Map<String, Integer> queryTermFreq = buildTermFreq(queryText);
        if (queryTermFreq.isEmpty()) return List.of();

        lock.readLock().lock();
        try {
            int n = docTermFreq.size();
            if (n == 0) return List.of();
            float avgdl = (float) totalDocLength / n;

            Map<String, Float> scores = new HashMap<>();
            score(queryTermFreq.keySet(), invertedIndex, docTermFreq, docLengths, n, avgdl, 1f, accept, scores);
            if (!headerTermFreq.isEmpty()) {
                float avgHeader = (float) totalHeaderLength / headerTermFreq.size();
                score(queryTermFreq.keySet(), headerIndex, headerTermFreq, headerLengths, n, avgHeader,
                        HEADER_WEIGHT, accept, scores);
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

    /** Ajoute à {@code scores} la contribution BM25 d'un champ. Appelée sous verrou de lecture. */
    private void score(Set<String> queryTokens, Map<String, Set<String>> index,
                       Map<String, Map<String, Integer>> termFreqs, Map<String, Integer> lengths,
                       int n, float avgLength, float weight,
                       java.util.function.BiPredicate<String, String> accept, Map<String, Float> scores) {
        for (String token : queryTokens) {
            Set<String> postings = index.get(token);
            if (postings == null || postings.isEmpty()) continue;

            int df = postings.size();
            float idf = (float) Math.log((n - df + 0.5) / (df + 0.5) + 1.0);

            for (String docId : postings) {
                if (accept != null && !accept.test(docIdentities.get(docId), docSources.get(docId))) continue;
                Map<String, Integer> tf = termFreqs.get(docId);
                if (tf == null) continue;
                float tfVal = tf.getOrDefault(token, 0);
                int dl = lengths.getOrDefault(docId, 1);
                float tfNorm = tfVal * (K1 + 1) / (tfVal + K1 * (1 - B + B * dl / avgLength));
                scores.merge(docId, weight * idf * tfNorm, Float::sum);
            }
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
            Integer docLen = docLengths.remove(id);
            if (docLen != null) totalDocLength -= docLen;
            for (String token : termFreq.keySet()) {
                Set<String> postings = invertedIndex.get(token);
                if (postings != null) {
                    postings.remove(id);
                    if (postings.isEmpty()) invertedIndex.remove(token);
                }
            }
        }
        Map<String, Integer> headerFreq = headerTermFreq.remove(id);
        if (headerFreq != null) {
            Integer headerLen = headerLengths.remove(id);
            if (headerLen != null) totalHeaderLength -= headerLen;
            for (String token : headerFreq.keySet()) {
                Set<String> postings = headerIndex.get(token);
                if (postings != null) {
                    postings.remove(id);
                    if (postings.isEmpty()) headerIndex.remove(token);
                }
            }
        }
        docTexts.remove(id);
        docSources.remove(id);
        docIdentities.remove(id);
        docHeaders.remove(id);
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
        // Split sur blancs + apostrophes (les élisions « l' », « d' »… n'apportent rien et
        // fusionnaient auparavant en tokens inexistants : « l'énergie » → « lénergie »,
        // introuvable par une recherche « énergie »). Les tirets restent dans les tokens bruts.
        for (String raw : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            // Nettoyer les caractères non significatifs en conservant le tiret à l'intérieur
            String cleaned = NON_WORD.matcher(raw).replaceAll("");
            cleaned = trimHyphens(cleaned); // tirets en début/fin
            if (cleaned.length() < MIN_TOKEN_LEN) continue;
            if (STOP_WORDS.contains(cleaned)) continue;
            freq.merge(normalize(cleaned), 1, Integer::sum);   // terme complet (ex: "porte-à-faux")
            if (cleaned.contains("-")) {
                for (String part : cleaned.split("-")) {
                    if (part.length() >= MIN_TOKEN_LEN && !STOP_WORDS.contains(part)) {
                        freq.merge(normalize(part), 1, Integer::sum);  // parties (ex: "porte", "faux")
                    }
                }
            }
        }
        return freq;
    }

    /**
     * Normalisation appliquée à l'identique à l'indexation et à la requête : diacritiques retirés
     * (« evenement » trouve « événement », saisie sans accents fréquente), ligatures dépliées,
     * puis racinisation légère du pluriel (« -s », « -x » final, mots de plus de trois lettres ;
     * « -aux » → « -al »). Volontairement minimale : une racinisation agressive fusionne des
     * termes distincts, et l'erreur est invisible — un mauvais chunk remonte, sans échec.
     */
    static String normalize(String token) {
        String folded = token.indexOf('œ') >= 0 || token.indexOf('æ') >= 0
                ? token.replace("œ", "oe").replace("æ", "ae") : token;
        folded = DIACRITICS.matcher(Normalizer.normalize(folded, Normalizer.Form.NFD)).replaceAll("");
        int len = folded.length();
        if (len > 4 && folded.endsWith("aux")) return folded.substring(0, len - 3) + "al";
        if (len > 3 && (folded.endsWith("s") || folded.endsWith("x")) && !folded.endsWith("ss")) {
            return folded.substring(0, len - 1);
        }
        return folded;
    }

    private static final java.util.regex.Pattern DIACRITICS = java.util.regex.Pattern.compile("\\p{M}+");

    /**
     * Retire les tirets en début et fin de token. Balayage linéaire plutôt qu'une regex
     * {@code ^-+|-+$} : l'alternative {@code -+$} backtracke en O(n²) sur une longue suite
     * de tirets (ReDoS polynomial signalé par CodeQL), et le trim manuel est de toute
     * façon plus rapide sur ce chemin chaud.
     */
    private static String trimHyphens(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') start++;
        while (end > start && s.charAt(end - 1) == '-') end--;
        return (start == 0 && end == s.length()) ? s : s.substring(start, end);
    }
}
