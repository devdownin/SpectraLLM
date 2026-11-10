package fr.spectra.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Données d'utilisateur dans les journaux : questions, reformulations et URL.
 *
 * <p>Les journaux sont écrits en clair sur disque ({@code data/logs}, 30 jours, 3 Go) et sur la
 * console du conteneur, donc dans tout agrégateur qui les collecte. Une question posée au RAG peut
 * contenir ce que l'utilisateur ne veut pas voir ailleurs que dans sa réponse ; une URL d'ingestion
 * peut porter un jeton dans sa requête. Ce que cette classe produit suffit à diagnostiquer
 * (longueur, corrélation entre lignes d'un même processus) sans rien divulguer.</p>
 *
 * <p>Le contenu intégral reste disponible pour le débogage, sur demande explicite :
 * {@code SPECTRA_LOG_CONTENT=true} (ou {@code -Dspectra.log.content=true}). C'est un réglage
 * d'exploitation à ne pas laisser en production.</p>
 */
public final class LogSafe {

    private static final boolean CONTENT = Boolean.parseBoolean(
            System.getProperty("spectra.log.content", System.getenv().getOrDefault("SPECTRA_LOG_CONTENT", "false")));
    /**
     * Clé tirée à chaque démarrage : la même question donne la même empreinte pendant la vie du
     * processus (corrélation entre deux lignes), mais l'empreinte ne se rejoue pas par dictionnaire
     * et ne se compare pas d'un redémarrage à l'autre.
     */
    private static final byte[] KEY = new byte[32];

    static {
        new SecureRandom().nextBytes(KEY);
    }

    private LogSafe() {
    }

    /** Texte d'utilisateur : intégral si {@code SPECTRA_LOG_CONTENT=true}, sinon longueur et empreinte. */
    public static String text(String value) {
        return text(value, CONTENT);
    }

    static String text(String value, boolean content) {
        if (value == null) return "null";
        if (content) return value;
        return "[" + value.length() + " car., #" + fingerprint(value) + "]";
    }

    /** URL sans identifiants, sans requête et sans fragment : l'hôte et le chemin suffisent à diagnostiquer. */
    public static String url(String value) {
        return url(value, CONTENT);
    }

    static String url(String value, boolean content) {
        if (value == null) return "null";
        if (content) return value;
        try {
            URI uri = URI.create(value.strip());
            if (uri.getHost() == null) return "[URL illisible, " + value.length() + " car.]";
            StringBuilder out = new StringBuilder();
            if (uri.getScheme() != null) out.append(uri.getScheme()).append("://");
            out.append(uri.getHost());
            if (uri.getPort() >= 0) out.append(':').append(uri.getPort());
            if (uri.getRawPath() != null) out.append(uri.getRawPath());
            if (uri.getRawQuery() != null || uri.getRawUserInfo() != null) out.append(" [paramètres masqués]");
            return out.toString();
        } catch (IllegalArgumentException invalid) {
            return "[URL illisible, " + value.length() + " car.]";
        }
    }

    private static String fingerprint(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponible", e);
        }
    }
}
