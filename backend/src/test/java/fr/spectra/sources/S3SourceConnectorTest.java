package fr.spectra.sources;

import org.junit.jupiter.api.Test;
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
