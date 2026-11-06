package fr.spectra.sources;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Component
public class S3SourceConnector implements SourceConnector {
    private final SourceHttpClient addresses;
    public S3SourceConnector(SourceHttpClient addresses) { this.addresses = addresses; }
    @Override public SourceModels.Type type() { return SourceModels.Type.S3; }
    @Override public void validate(SourceModels.Config config) {
        if (config.bucket() == null || !config.bucket().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,254}"))
            throw new IllegalArgumentException("A valid bucket name is required");
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            URI endpoint = addresses.validate(config.endpoint());
            if (endpoint.getQuery() != null || (endpoint.getPath() != null && !endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/")))
                throw new IllegalArgumentException("S3 endpoint must be an origin without path or query");
        }
        boolean access = config.accessKeyEnv() != null && !config.accessKeyEnv().isBlank();
        boolean secret = config.secretKeyEnv() != null && !config.secretKeyEnv().isBlank();
        if (access != secret) throw new IllegalArgumentException("Specify both credential environment variable names, or use the default AWS chain");
    }
    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A referenced S3 credential environment variable is missing");
        return value;
    }
    S3Client client(SourceModels.Config config) {
        validate(config);
        var builder = S3Client.builder().region(Region.of(config.region() == null || config.region().isBlank() ? "us-east-1" : config.region()))
                .httpClientBuilder(ApacheHttpClient.builder().dnsResolver(addresses::resolveAllowed)
                        .proxyConfiguration(software.amazon.awssdk.http.apache.ProxyConfiguration.builder()
                                .useSystemPropertyValues(false).useEnvironmentVariableValues(false).build())
                        .connectionTimeout(Duration.ofSeconds(10)).socketTimeout(Duration.ofSeconds(30)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofMinutes(2)).apiCallAttemptTimeout(Duration.ofSeconds(40)));
        if (config.endpoint() != null && !config.endpoint().isBlank())
            builder.endpointOverride(URI.create(config.endpoint())).forcePathStyle(true);
        if (config.accessKeyEnv() != null && !config.accessKeyEnv().isBlank()) {
            AwsCredentials credentials = config.sessionTokenEnv() != null && !config.sessionTokenEnv().isBlank()
                    ? AwsSessionCredentials.create(env(config.accessKeyEnv()), env(config.secretKeyEnv()), env(config.sessionTokenEnv()))
                    : AwsBasicCredentials.create(env(config.accessKeyEnv()), env(config.secretKeyEnv()));
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        } else builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        return builder.build();
    }
    @Override public SourceModels.ConnectionTest test(SourceModels.Config config) throws Exception {
        try (S3Client client = client(config)) {
            var page = client.listObjectsV2(r -> r.bucket(config.bucket()).prefix(prefix(config)).maxKeys(1));
            return new SourceModels.ConnectionTest(true, "Bucket listing is accessible. Object reads are checked during synchronization.", page.contents().size());
        } catch (S3Exception e) { throw problem(e); }
    }
    @Override public boolean scan(SourceModels.Config config, Sink sink) throws Exception {
        try (S3Client client = client(config)) {
            String token = null;
            int count = 0;
            do {
                sink.checkCancelled();
                var page = client.listObjectsV2(ListObjectsV2Request.builder().bucket(config.bucket())
                        .prefix(prefix(config)).maxKeys(200).continuationToken(token).build());
                for (S3Object object : page.contents()) {
                    sink.checkCancelled();
                    if (object.key().endsWith("/") || !SourceSupport.selected(object.key(), config)) continue;
                    if (count++ >= config.maxDocuments()) return false;
                    if (object.size() > config.maxBytes()) throw new SourceSupport.SourceProblem("An S3 object exceeds the configured size limit");
                    byte[] bytes;
                    try (var in = client.getObject(GetObjectRequest.builder().bucket(config.bucket()).key(object.key()).ifMatch(object.eTag()).build())) {
                        bytes = in.readNBytes((int)config.maxBytes() + 1);
                    }
                    if (bytes.length > config.maxBytes()) throw new SourceSupport.SourceProblem("An S3 object exceeds the configured size limit");
                    String title = object.key().substring(object.key().lastIndexOf('/') + 1);
                    sink.accept(new Item(object.key(), title, null, title, SourceSupport.hash(bytes), bytes,
                            Map.of("sourceModifiedAt", object.lastModified().toString(), "bucket", config.bucket(), "objectKey", object.key())));
                }
                String previous = token;
                token = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
                if (Boolean.TRUE.equals(page.isTruncated()) && (token == null || token.equals(previous)))
                    throw new SourceSupport.SourceProblem("S3 listing did not supply a usable continuation token");
            } while (token != null);
            return true;
        } catch (S3Exception e) { throw problem(e); }
    }
    private static String prefix(SourceModels.Config config) { return config.prefix() == null ? "" : config.prefix(); }
    private static SourceSupport.SourceProblem problem(S3Exception e) {
        return new SourceSupport.SourceProblem("S3 request failed (HTTP " + e.statusCode() + "). Check bucket permissions and connection settings.");
    }
}
