package fr.spectra.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LocalFolderConnectorTest {
    @TempDir Path temp;

    @Test void onlySelectedFilesInsideConfiguredDepthAreDiscovered() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Files.writeString(root.resolve("guide.md"), "guide");
        Files.writeString(root.resolve("secret.txt"), "secret");
        Files.write(root.resolve("image.png"), new byte[] {1});
        Path nested = Files.createDirectory(root.resolve("nested"));
        Files.writeString(nested.resolve("nested.md"), "nested");
        var connector = new LocalFolderConnector(root.toString());
        var sink = new CollectingSink();
        assertThat(connector.scan(config(root, List.of("md"), 0, 10), sink)).isTrue();
        assertThat(sink.items).extracting(SourceConnector.Item::externalId).containsExactly("guide.md");
        assertThat(new String(sink.items.getFirst().content(), StandardCharsets.UTF_8)).isEqualTo("guide");
    }

    @Test void symlinksCannotImportFilesOrFoldersOutsideAllowedRoot() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("private.txt"), "private");
        Files.createSymbolicLink(root.resolve("linked-file.txt"), outside.resolve("private.txt"));
        Files.createSymbolicLink(root.resolve("linked-folder"), outside);
        var connector = new LocalFolderConnector(root.toString());
        var sink = new CollectingSink();
        assertThat(connector.scan(config(root, List.of(), 3, 10), sink)).isTrue();
        assertThat(sink.items).isEmpty();
        assertThatThrownBy(() -> connector.test(config(root.resolve("linked-folder"), List.of(), 3, 10)))
                .isInstanceOf(SourceSupport.SourceProblem.class);
    }

    @Test void capMarksDiscoveryIncompleteSoOrchestratorCannotTreatUnvisitedFilesAsDeleted() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Files.writeString(root.resolve("first.txt"), "first");
        Files.writeString(root.resolve("second.txt"), "second");
        var sink = new CollectingSink();
        assertThat(new LocalFolderConnector(root.toString()).scan(config(root, List.of(), 2, 1), sink)).isFalse();
        assertThat(sink.items).hasSize(1);
    }

    @Test void unavailableFolderIsAnErrorAndNeverAValidEmptyListing() {
        var connector = new LocalFolderConnector(temp.toString());
        assertThatThrownBy(() -> connector.scan(config(temp.resolve("missing"), List.of(), 2, 10), new CollectingSink()))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> connector.validate(config(temp.resolveSibling("other"), List.of(), 2, 10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void oversizedDocumentIsRejectedBeforeItsBytesReachIndexer() throws Exception {
        Path root = Files.createDirectory(temp.resolve("allowed"));
        Files.write(root.resolve("large.txt"), new byte[1024 * 1024 + 1]);
        var sink = new CollectingSink();
        assertThatThrownBy(() -> new LocalFolderConnector(root.toString()).scan(config(root, List.of(), 2, 10), sink))
                .isInstanceOf(SourceSupport.SourceProblem.class).hasMessageContaining("size limit");
        assertThat(sink.items).isEmpty();
    }

    static SourceModels.Config config(Path root, List<String> extensions, int depth, int max) {
        return new SourceModels.Config(root.toString(), null, null, null, null, null, null, null, null, null, extensions, List.of(), max, depth, 1);
    }
    static class CollectingSink implements SourceConnector.Sink {
        final List<SourceConnector.Item> items = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        @Override public void accept(SourceConnector.Item item) { items.add(item); }
        @Override public void deleted(String externalId) { deleted.add(externalId); }
        @Override public void checkCancelled() {}
    }
}
