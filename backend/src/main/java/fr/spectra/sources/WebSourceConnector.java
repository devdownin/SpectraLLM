package fr.spectra.sources;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class WebSourceConnector implements SourceConnector {
    private final SourceHttpClient http;
    public WebSourceConnector(SourceHttpClient http) { this.http = http; }
    @Override public SourceModels.Type type() { return SourceModels.Type.WEB; }
    @Override public void validate(SourceModels.Config config) {
        if (config.url() == null || config.url().isBlank()) throw new IllegalArgumentException("A starting URL is required");
        URI start = http.validate(config.url());
        if (config.sitemapUrl() != null && !config.sitemapUrl().isBlank()) {
            URI sitemap = http.validate(config.sitemapUrl());
            if (!origin(start).equals(origin(sitemap))) throw new IllegalArgumentException("Sitemap must share the starting URL's origin");
        }
    }
    @Override public SourceModels.ConnectionTest test(SourceModels.Config config) throws Exception {
        validate(config);
        var response = http.get(config.url(), config.maxBytes());
        if (response.status() != 200) throw new SourceSupport.SourceProblem("Starting URL returned HTTP " + response.status() + ". Use the final URL if it redirects.");
        return new SourceModels.ConnectionTest(true, "Starting URL is accessible. Crawl stays within its origin and path.", 1);
    }
    private record Pending(URI uri, int depth) {}
    @Override public boolean scan(SourceModels.Config config, Sink sink) throws Exception {
        validate(config);
        URI start = URI.create(config.url()).normalize();
        var robotsResponse = http.get(origin(start) + "/robots.txt", Math.min(config.maxBytes(), 512 * 1024));
        if (robotsResponse.status() != 200 && robotsResponse.status() != 404 && robotsResponse.status() != 410)
            throw new SourceSupport.SourceProblem("robots.txt returned HTTP " + robotsResponse.status() + "; crawl deferred");
        RobotsPolicy robots = RobotsPolicy.parse(new String(robotsResponse.bytes(), StandardCharsets.UTF_8));
        Deque<Pending> pending = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();
        enqueue(start, 0, start, pending, queued);
        boolean discoveryComplete = true;
        if (config.sitemapUrl() != null && !config.sitemapUrl().isBlank())
            discoveryComplete = sitemaps(URI.create(config.sitemapUrl()), start, config, sink, pending, queued);
        int count = 0;
        while (!pending.isEmpty()) {
            sink.checkCancelled();
            Pending current = pending.removeFirst();
            URI uri = current.uri;
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (!robots.allows(path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())) || SourceSupport.excluded(path, config)) continue;
            if (count++ >= config.maxDocuments()) return false;
            var response = http.get(uri.toString(), config.maxBytes());
            if (response.status() == 404 || response.status() == 410) { sink.deleted(uri.toString()); continue; }
            if (response.status() != 200) throw new SourceSupport.SourceProblem("A web page returned HTTP " + response.status() + ". No missing documents were removed.");
            String mime = response.contentType().toLowerCase(Locale.ROOT);
            boolean html = mime.contains("text/html") || mime.isBlank();
            String filename = filename(uri, mime);
            String title = uri.getPath();
            byte[] content = response.bytes();
            if (html) {
                Document page = Jsoup.parse(new ByteArrayInputStream(content), null, uri.toString());
                title = page.title().isBlank() ? uri.getPath() : page.title();
                Set<String> directives = new HashSet<>();
                page.select("meta[name]").stream()
                        .filter(meta -> meta.attr("name").equalsIgnoreCase("robots") || meta.attr("name").equalsIgnoreCase("SpectraBot"))
                        .forEach(meta -> directives.addAll(Arrays.asList(meta.attr("content").toLowerCase(Locale.ROOT).split("[,\\s]+"))));
                if (current.depth < config.maxDepth() && !directives.contains("nofollow") && !directives.contains("none")) {
                    for (var link : page.select("a[href]")) {
                        try {
                            URI target = URI.create(link.absUrl("href").split("#", 2)[0]).normalize();
                            if (queued.size() >= config.maxDocuments() * 4L) discoveryComplete = false;
                            else enqueue(target, current.depth + 1, start, pending, queued);
                        } catch (IllegalArgumentException ignored) { /* A malformed link does not invalidate a fetched document. */ }
                    }
                }
                if (directives.contains("noindex") || directives.contains("none")) continue;
            }
            if (!SourceSupport.selected(filename, config)) continue;
            sink.accept(new Item(uri.toString(), SourceSupport.bounded(title, 512), uri.toString(), filename,
                    SourceSupport.hash(content), content, Map.of("sourceUrl", uri.toString())));
        }
        return discoveryComplete;
    }
    private boolean sitemaps(URI first, URI start, SourceModels.Config config, Sink sink,
                             Deque<Pending> pending, Set<String> queued) throws Exception {
        Deque<URI> maps = new ArrayDeque<>();
        Set<URI> seen = new HashSet<>();
        maps.add(first);
        while (!maps.isEmpty()) {
            sink.checkCancelled();
            URI uri = maps.removeFirst();
            if (!seen.add(uri)) continue;
            if (seen.size() > 50) return false;
            var response = http.get(uri.toString(), config.maxBytes());
            if (response.status() != 200) throw new SourceSupport.SourceProblem("Sitemap could not be read (HTTP " + response.status() + ")");
            // jsoup's XML parser never resolves external entities or DTDs.
            var xml = Jsoup.parse(new ByteArrayInputStream(response.bytes()), null, uri.toString(), Parser.xmlParser());
            if (xml.select("urlset,sitemapindex").isEmpty()) throw new SourceSupport.SourceProblem("Sitemap is not a supported XML sitemap");
            for (var loc : xml.select("url > loc,sitemap > loc")) {
                URI target = URI.create(loc.text().trim()).normalize();
                if (!origin(start).equals(origin(target))) continue;
                if (loc.parent() != null && loc.parent().normalName().equals("sitemap")) {
                    if (maps.size() >= 50) return false;
                    maps.add(target);
                } else {
                    if (queued.size() >= config.maxDocuments()) return false;
                    enqueue(target, 0, start, pending, queued);
                }
            }
        }
        return true;
    }
    private static void enqueue(URI uri, int depth, URI start, Deque<Pending> queue, Set<String> queued) {
        if (within(uri, start) && queued.add(uri.toString())) queue.addLast(new Pending(uri, depth));
    }
    static boolean within(URI uri, URI start) {
        if (uri.toString().length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || !origin(uri).equals(origin(start))) return false;
        String base = normalizedPath(start);
        String path = normalizedPath(uri);
        if (base == null || path == null) return false;
        if (base.isEmpty()) base = "/";
        return path.equals(base) || path.startsWith(base.endsWith("/") ? base : base + "/");
    }
    private static String normalizedPath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return "/";
        if (path.indexOf('\\') >= 0) return null;
        try { return new URI(null, null, path, null).normalize().getPath(); }
        catch (java.net.URISyntaxException e) { return null; }
    }
    private static String origin(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
        return scheme + "://" + (uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT)) + (port < 0 ? "" : ":" + port);
    }
    private static String filename(URI uri, String mime) {
        String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
        if (mime.contains("text/html") || mime.isBlank()) return "page.html";
        if (mime.contains("application/pdf")) return "document.pdf";
        if (mime.contains("wordprocessingml")) return "document.docx";
        if (mime.contains("msword")) return "document.doc";
        if (mime.contains("json")) return "document.json";
        if (mime.contains("xml")) return "document.xml";
        if (mime.contains("text/plain")) return SourceSupport.supported(name) ? name : "document.txt";
        return name.isBlank() ? "document.bin" : name;
    }
}
