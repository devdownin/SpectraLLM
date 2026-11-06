package fr.spectra.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repère dans une question les <b>identifiants exacts</b> — clés de ticket, numéros de PR, noms
 * de variables ou de métriques, noms de produit en CamelCase — qu'un embedding représente mal
 * et qu'une tokenisation BM25 découpe ({@code stream.timebox_finalized}, {@code ENG-39100}).
 *
 * <p>Graphe d'entités <i>léger</i> : pas d'extraction à l'ingestion ni de réindexation. Les
 * entités de la question servent de clés de recherche exacte (voir {@link ExactMatchRetriever}) ;
 * les chunks qui les mentionnent rejoignent les candidats, et c'est le reranker qui tranche.</p>
 *
 * <p>Les sigles courts ({@code GPU}, {@code SLO}) sont volontairement ignorés : trop fréquents
 * pour discriminer, la recherche lexicale les couvre déjà.</p>
 */
public final class EntityExtractor {

    /** Clé de ticket : ENG-39100, DES-412009. */
    private static final Pattern TICKET = Pattern.compile("\\b[A-Z][A-Z0-9]{1,9}-\\d{1,7}\\b");
    /** Référence de PR ou d'issue : #20501. */
    private static final Pattern NUMBERED_REF = Pattern.compile("(?<![\\w&])#\\d{3,7}\\b");
    /** snake_case ou notation pointée en minuscules : max_file_size, stream.timebox_finalized. */
    private static final Pattern CODE_NAME = Pattern.compile("\\b[a-z][a-z0-9]*(?:[._][a-z0-9]+)+\\b");
    /** CamelCase : MedThink, ClickHouse. */
    private static final Pattern CAMEL_CASE = Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\\b");

    /** Écarte « e.g », « i.e » et autres abréviations pointées, trop courtes pour être un identifiant. */
    private static final int MIN_CODE_NAME_LENGTH = 6;

    private EntityExtractor() {}

    /**
     * Entités de {@code text}, sans doublon, dans l'ordre d'apparition.
     *
     * @param max nombre maximal d'entités renvoyées (les premières)
     */
    public static List<String> extract(String text, int max) {
        if (text == null || text.isBlank() || max <= 0) return List.of();
        // Indexé par position : l'ordre d'apparition prime sur le type d'identifiant.
        java.util.TreeMap<Integer, String> byPosition = new java.util.TreeMap<>();
        collect(TICKET, text, byPosition, 0);
        collect(NUMBERED_REF, text, byPosition, 0);
        collect(CODE_NAME, text, byPosition, MIN_CODE_NAME_LENGTH);
        collect(CAMEL_CASE, text, byPosition, 0);

        Set<String> seen = new LinkedHashSet<>();
        List<String> entities = new ArrayList<>();
        for (String entity : byPosition.values()) {
            if (seen.add(entity.toLowerCase(Locale.ROOT)) && entities.size() < max) entities.add(entity);
        }
        return entities;
    }

    private static void collect(Pattern pattern, String text, java.util.TreeMap<Integer, String> out, int minLength) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String value = m.group();
            if (value.length() < minLength) continue;
            out.putIfAbsent(m.start(), value);
        }
    }
}
