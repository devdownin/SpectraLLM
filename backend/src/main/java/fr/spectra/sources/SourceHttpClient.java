package fr.spectra.sources;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import java.net.*;
import java.time.Duration;
import java.util.*;

/** No redirects, no cookies; address checks also run against the IP used by the connection. */
@Component
public class SourceHttpClient {
    private final Set<String> allowedHosts;
    private final WebClient client;
    public SourceHttpClient(@Value("${spectra.sources.allowed-hosts:}") String hosts) {
        allowedHosts = new HashSet<>(Arrays.stream(hosts.toLowerCase(Locale.ROOT).split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        var http = HttpClient.create().followRedirect(false).responseTimeout(Duration.ofSeconds(30))
                .doAfterResolve((connection, address) -> {
                    if (address instanceof InetSocketAddress socket && socket.getAddress() != null
                            && !allowedHosts.contains(socket.getHostString().toLowerCase(Locale.ROOT))
                            && forbidden(socket.getAddress())) throw new IllegalArgumentException("Source network address is not allowed");
                });
        client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024 + 1)).build();
    }
    public URI validate(String url) {
        try {
            URI uri = URI.create(url).normalize();
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Use an HTTP(S) URL without credentials or fragments");
            if (!allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
                    if (forbidden(address)) throw new IllegalArgumentException("Private source hosts must be explicitly allowed by the administrator");
            }
            return uri;
        } catch (UnknownHostException e) { throw new IllegalArgumentException("Source host could not be resolved"); }
    }
    public InetAddress[] resolveAllowed(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (!allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            for (InetAddress address : addresses) if (forbidden(address))
                throw new UnknownHostException("Source network address is not allowed");
        }
        return addresses;
    }
    public Response get(String url, long maxBytes) throws Exception {
        URI uri = validate(url);
        Response result = client.get().uri(uri).header("User-Agent", "SpectraBot/1.0")
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status != 200) return response.releaseBody().thenReturn(new Response(status,
                            response.headers().contentType().map(Object::toString).orElse(""), new byte[0]));
                    long length = response.headers().contentLength().orElse(0);
                    if (length > maxBytes) return response.releaseBody().then(reactor.core.publisher.Mono.error(
                            new IllegalArgumentException("Source response exceeds the configured size limit")));
                    return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(bytes -> {
                        if (bytes.length > maxBytes) throw new IllegalArgumentException("Source response exceeds the configured size limit");
                        return new Response(status, response.headers().contentType().map(Object::toString).orElse(""), bytes);
                    });
                }).block(Duration.ofSeconds(45));
        if (result == null) throw new SourceSupport.SourceProblem("Source returned no response");
        return result;
    }
    public record Response(int status, String contentType, byte[] bytes) {}
    static boolean forbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] b = address.getAddress();
        if (b.length == 4) return ((b[0] & 255) == 100 && (b[1] & 255) >= 64 && (b[1] & 255) <= 127)
                || (b[0] & 255) == 0 || (b[0] & 255) >= 240;
        return (b[0] & 0xfe) == 0xfc;
    }
}
