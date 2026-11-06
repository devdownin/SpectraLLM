package fr.spectra.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 *
 * <p><b>Pas d'expression régulière sur la question.</b> La question est une donnée non maîtrisée.
 * Des motifs à quantificateurs imbriqués ({@code [a-z0-9]*(?:[._][a-z0-9]+)+}) peuvent
 * rétro-pister de façon polynomiale (alerte CodeQL « polynomial regular expression »). L'analyse
 * se fait donc en un seul passage linéaire, par découpage en jetons puis contrôles simples ; seule
 * la clé de ticket garde un motif, ancré et à répétitions bornées. L'entrée est en outre tronquée.</p>
 */
public final class EntityExtractor {

    /** Clé de ticket : ENG-39100, DES-412009. Ancré, répétitions bornées : temps linéaire. */
    private static final Pattern TICKET = Pattern.compile("[A-Z][A-Z0-9]{1,9}-[0-9]{1,7}");

    /** Une question plus longue est tronquée : les identifiants utiles sont dans ses premières lignes. */
    static final int MAX_INPUT_CHARS = 4000;
    /** Écarte « e.g », « i.e » et autres abréviations pointées, trop courtes pour être un identifiant. */
    private static final int MIN_CODE_NAME_LENGTH = 6;
    /**
     * Un identifiant réel tient en quelques dizaines de caractères. Au-delà, c'est du bruit (ou une
     * attaque) : on ne lance pas une recherche exacte avec une aiguille de plusieurs milliers de signes.
     */
    static final int MAX_ENTITY_LENGTH = 100;

    private EntityExtractor() {}

    /**
     * Entités de {@code text}, sans doublon (casse ignorée), dans l'ordre d'apparition.
     *
     * @param max nombre maximal d'entités renvoyées (les premières)
     */
    public static List<String> extract(String text, int max) {
        if (text == null || text.isBlank() || max <= 0) return List.of();
        String input = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;

        Set<String> seen = new HashSet<>();
        List<String> entities = new ArrayList<>();
        int n = input.length();
        int i = 0;
        while (i < n && entities.size() < max) {
            while (i < n && !isTokenChar(input.charAt(i))) i++;
            int start = i;
            while (i < n && isTokenChar(input.charAt(i))) i++;
            if (i == start) continue;
            // « &#8217; » est une entité HTML, pas une référence de PR.
            if (start > 0 && input.charAt(start - 1) == '&') continue;

            String token = trimSeparators(input, start, i);
            if (!token.isEmpty() && isEntity(token) && seen.add(token.toLowerCase(Locale.ROOT))) {
                entities.add(token);
            }
        }
        return entities;
    }

    private static boolean isEntity(String token) {
        if (token.length() > MAX_ENTITY_LENGTH) return false;
        return TICKET.matcher(token).matches()
                || isNumberedReference(token)
                || isCodeName(token)
                || isCamelCase(token);
    }

    /** Référence de PR ou d'issue : « # » suivi de 3 à 7 chiffres. */
    private static boolean isNumberedReference(String token) {
        int digits = token.length() - 1;
        if (token.charAt(0) != '#' || digits < 3 || digits > 7) return false;
        for (int k = 1; k < token.length(); k++) {
            if (!isDigit(token.charAt(k))) return false;
        }
        return true;
    }

    /**
     * snake_case ou notation pointée en minuscules ({@code max_file_size},
     * {@code stream.timebox_finalized}) : au moins un séparateur, jamais en bordure ni doublé.
     */
    private static boolean isCodeName(String token) {
        if (token.length() < MIN_CODE_NAME_LENGTH || !isLower(token.charAt(0))) return false;
        boolean hasSeparator = false;
        boolean previousWasSeparator = false;
        for (int k = 0; k < token.length(); k++) {
            char c = token.charAt(k);
            if (c == '.' || c == '_') {
                if (previousWasSeparator) return false;
                hasSeparator = true;
                previousWasSeparator = true;
            } else if (isLower(c) || isDigit(c)) {
                previousWasSeparator = false;
            } else {
                return false;
            }
        }
        return hasSeparator && !previousWasSeparator;
    }

    /** CamelCase : au moins deux segments « Majuscule + minuscules/chiffres » ({@code MedThink}). */
    private static boolean isCamelCase(String token) {
        int segments = 0;
        int k = 0;
        while (k < token.length()) {
            if (!isUpper(token.charAt(k))) return false;
            int run = 0;
            k++;
            while (k < token.length() && (isLower(token.charAt(k)) || isDigit(token.charAt(k)))) {
                k++;
                run++;
            }
            if (run == 0) return false;
            segments++;
        }
        return segments >= 2;
    }

    private static String trimSeparators(String s, int start, int end) {
        int a = start;
        int b = end;
        while (a < b && isEdgeSeparator(s.charAt(a))) a++;
        while (b > a && isEdgeSeparator(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isEdgeSeparator(char c) {
        return c == '.' || c == '_' || c == '-';
    }

    private static boolean isTokenChar(char c) {
        return isLower(c) || isUpper(c) || isDigit(c) || c == '.' || c == '_' || c == '-' || c == '#';
    }

    private static boolean isLower(char c) { return c >= 'a' && c <= 'z'; }
    private static boolean isUpper(char c) { return c >= 'A' && c <= 'Z'; }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
}
