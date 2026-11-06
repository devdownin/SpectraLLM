package fr.spectra.sources;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

final class SourceSupport {
    private static final Set<String> SUPPORTED = Set.of("txt", "md", "markdown", "csv", "json", "xml", "html", "htm", "pdf", "doc", "docx", "avro");
    private SourceSupport() {}
    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static boolean selected(String path, SourceModels.Config config) {
        String name = path.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        if (!SUPPORTED.contains(ext)) return false;
        if (!config.extensions().isEmpty() && config.extensions().stream()
                .map(s -> s.replaceFirst("^\\.", "").toLowerCase(Locale.ROOT)).noneMatch(ext::equals)) return false;
        return !excluded(path, config);
    }
    static boolean supported(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
    static boolean excluded(String path, SourceModels.Config config) {
        Path relative = Path.of(path.replaceFirst("^/+", ""));
        return config.excludePatterns().stream().anyMatch(pattern -> {
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            return matcher.matches(relative) || matcher.matches(relative.getFileName());
        });
    }
    static void validateFilters(SourceModels.Config config) {
        for (String pattern : config.excludePatterns()) {
            try { FileSystems.getDefault().getPathMatcher("glob:" + pattern); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid exclusion pattern"); }
        }
    }
    static String bounded(String text, int max) { return text == null ? null : text.substring(0, Math.min(max, text.length())); }
    static String failure(Exception e) {
        if (e instanceof SourceProblem p) return bounded(p.getMessage(), 1000);
        // SDK/HTTP exception bodies can contain signed URLs and credentials. Never persist them.
        return "Operation failed (" + e.getClass().getSimpleName() + "). Check the source connection and retry.";
    }
    static class SourceProblem extends java.io.IOException {
        SourceProblem(String message) { super(message); }
    }
}
