package fr.spectra.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class S3SourceConnectorTest {
    @Test void paginatedListingUsesContinuationTokenAndConfiguredPrefixWithoutDuplicatingItems() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(object("docs/one.txt", "one-tag")).isTruncated(true).nextContinuationToken("page-2").build())
                .thenReturn(ListObjectsV2Response.builder().contents(object("docs/two.txt", "two-tag")).isTruncated(false).build());
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(a -> stream("content for " + ((GetObjectRequest)a.getArgument(0)).key()));
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector(client).scan(config(10), sink)).isTrue();
        assertThat(sink.items).extracting(SourceConnector.Item::externalId).containsExactly("docs/one.txt", "docs/two.txt");
        var requests = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client, times(2)).listObjectsV2(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(r -> { assertThat(r.bucket()).isEqualTo("knowledge"); assertThat(r.prefix()).isEqualTo("docs/"); });
        assertThat(requests.getAllValues().getFirst().continuationToken()).isNull();
        assertThat(requests.getAllValues().getLast().continuationToken()).isEqualTo("page-2");
    }

    @Test void documentCapReturnsIncompleteAfterFirstDocumentRatherThanDroppingItsContent() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(object("docs/one.txt", "one-tag"), object("docs/two.txt", "two-tag")).isTruncated(false).build());
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(a -> stream("first document"));
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector(client).scan(config(1), sink)).isFalse();
        assertThat(sink.items).extracting(SourceConnector.Item::externalId).containsExactly("docs/one.txt");
        verify(client, times(1)).getObject(any(GetObjectRequest.class));
    }

    @Test void deniedBucketListingIsReportedAsFailureAndNeverAsAnEmptyCompleteDiscovery() {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(S3Exception.builder().statusCode(403)
                .message("https://signed.invalid/?secret=do-not-log").build());
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThatThrownBy(() -> connector(client).scan(config(10), sink))
                .isInstanceOf(SourceSupport.SourceProblem.class).hasMessageContaining("HTTP 403")
                .hasMessageNotContaining("signed.invalid").hasMessageNotContaining("do-not-log");
        assertThat(sink.items).isEmpty();
        verify(client, never()).getObject(any(GetObjectRequest.class));
    }

    @Test void objectReadIsConditionedOnListedEtagAndSuppliesExactBytesToIndexer() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(object("docs/guide.txt", "\"listed-etag\"")).isTruncated(false).build());
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(a -> stream("Résumé français"));
        var sink = new LocalFolderConnectorTest.CollectingSink();
        assertThat(connector(client).scan(config(10), sink)).isTrue();
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().ifMatch()).isEqualTo("\"listed-etag\"");
        assertThat(request.getValue().key()).isEqualTo("docs/guide.txt");
        assertThat(sink.items.getFirst().content()).isEqualTo("Résumé français".getBytes(StandardCharsets.UTF_8));
        assertThat(sink.items.getFirst().metadata()).containsEntry("bucket", "knowledge").containsEntry("objectKey", "docs/guide.txt");
    }


    @Test void actualS3SdkSignsPathStyleMinioRequestsAndStreamsListedObject() throws Exception {
        withTestCredentials(() -> {
            try (var server = s3Server()) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "application/xml").setBody("""
                        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                          <Name>knowledge</Name><Prefix>docs/</Prefix><KeyCount>1</KeyCount><MaxKeys>200</MaxKeys><IsTruncated>false</IsTruncated>
                          <Contents><Key>docs/guide.txt</Key><LastModified>2026-11-01T10:00:00Z</LastModified><ETag>&quot;stable-etag&quot;</ETag><Size>5</Size><StorageClass>STANDARD</StorageClass></Contents>
                        </ListBucketResult>
                        """));
                server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain").setHeader("ETag", "\"stable-etag\"").setBody("Guide"));
                var connector = new S3SourceConnector(new SourceHttpClient("127.0.0.1"));
                var config = endpointConfig(server, "docs/", null, null);
                var sink = new LocalFolderConnectorTest.CollectingSink();
                assertThat(connector.scan(config, sink)).isTrue();
                assertThat(sink.items).extracting(SourceConnector.Item::externalId).containsExactly("docs/guide.txt");
                assertThat(sink.items.getFirst().content()).isEqualTo("Guide".getBytes(StandardCharsets.UTF_8));
                var listing = server.takeRequest(2, TimeUnit.SECONDS);
                var download = server.takeRequest(2, TimeUnit.SECONDS);
                assertThat(listing).isNotNull(); assertThat(download).isNotNull();
                assertThat(listing.getPath()).startsWith("/knowledge?").contains("list-type=2", "prefix=docs%2F");
                assertThat(listing.getHeader("Authorization")).contains("AWS4-HMAC-SHA256", "Credential=spectra-test-access/");
                assertThat(download.getPath()).isEqualTo("/knowledge/docs/guide.txt");
                assertThat(download.getHeader("If-Match")).isEqualTo("\"stable-etag\"");
            }
        });
    }

    @Test void actualSdkConnectionCheckRecognizesAccessibleEmptyBucket() throws Exception {
        withTestCredentials(() -> {
            try (var server = s3Server()) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "application/xml").setBody("""
                        <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                          <Name>knowledge</Name><KeyCount>0</KeyCount><MaxKeys>1</MaxKeys><IsTruncated>false</IsTruncated>
                        </ListBucketResult>
                        """));
                var result = new S3SourceConnector(new SourceHttpClient("127.0.0.1")).test(endpointConfig(server, null, null, null));
                assertThat(result.success()).isTrue();
                assertThat(result.sampleCount()).isZero();
                assertThat(result.message()).contains("listing");
            }
        });
    }

    @Test void setupRejectsIncompleteCredentialsAndNonOriginEndpointBeforeNetworking() throws Exception {
        try (var server = s3Server()) {
            var connector = new S3SourceConnector(new SourceHttpClient("127.0.0.1"));
            assertThatThrownBy(() -> connector.validate(endpointConfig(server, "docs/", "SPECTRA_TEST_ACCESS", null)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both credential");
            var base = endpointConfig(server, "docs/", null, null);
            var invalid = new SourceModels.Config(null, null, null, "knowledge", "docs/", base.endpoint() + "/bucket?token=hidden",
                    null, null, null, null, List.of(), List.of(), 10, 2, 1);
            assertThatThrownBy(() -> connector.validate(invalid)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("origin");
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test void missingReferencedCredentialsFailBeforeBucketRequestWithoutLeakingSecretNames() throws Exception {
        try (var server = s3Server()) {
            var connector = new S3SourceConnector(new SourceHttpClient("127.0.0.1"));
            assertThatThrownBy(() -> connector.client(endpointConfig(server, "docs/", "SPECTRA_TEST_MISSING_ACCESS_71A2", "SPECTRA_TEST_MISSING_SECRET_71A2")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("environment variable is missing")
                    .hasMessageNotContaining("71A2");
            assertThat(server.getRequestCount()).isZero();
        }
    }

    private static SourceModels.Config endpointConfig(MockWebServer server, String prefix, String access, String secret) {
        return new SourceModels.Config(null, null, null, "knowledge", prefix, "http://127.0.0.1:" + server.getPort(), null,
                access, secret, null, List.of(), List.of(), 10, 2, 1);
    }
    private static MockWebServer s3Server() throws Exception {
        var server = new MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0); return server;
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static void withTestCredentials(CheckedAction action) throws Exception {
        String access = System.getProperty("aws.accessKeyId");
        String secret = System.getProperty("aws.secretAccessKey");
        String session = System.getProperty("aws.sessionToken");
        try {
            System.setProperty("aws.accessKeyId", "spectra-test-access");
            System.setProperty("aws.secretAccessKey", "spectra-test-secret");
            System.clearProperty("aws.sessionToken");
            action.run();
        } finally {
            restore("aws.accessKeyId", access); restore("aws.secretAccessKey", secret); restore("aws.sessionToken", session);
        }
    }
    private static void restore(String key, String value) { if (value == null) System.clearProperty(key); else System.setProperty(key, value); }

    private static S3SourceConnector connector(S3Client client) {
        return new S3SourceConnector(mock(SourceHttpClient.class)) {
            @Override S3Client client(SourceModels.Config config) { return client; }
        };
    }
    private static SourceModels.Config config(int max) {
        return new SourceModels.Config(null, null, null, "knowledge", "docs/", null, "us-east-1", null, null, null, List.of(), List.of(), max, 2, 1);
    }
    private static S3Object object(String key, String etag) {
        return S3Object.builder().key(key).eTag(etag).size(32L).lastModified(Instant.parse("2026-11-01T10:00:00Z")).build();
    }
    private static ResponseInputStream<GetObjectResponse> stream(String body) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }
}
