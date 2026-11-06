package fr.spectra.sources;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Component
public class LocalFolderConnector implements SourceConnector {
    private final List<Path> allowedRoots;
    public LocalFolderConnector(@Value("${spectra.sources.local-roots:/app/sources}") String roots) {
        allowedRoots = Arrays.stream(roots.split(",")).filter(s -> !s.isBlank())
                .map(s -> Path.of(s.trim()).toAbsolutePath().normalize()).toList();
    }
    @Override public SourceModels.Type type() { return SourceModels.Type.LOCAL_FOLDER; }
    @Override public void validate(SourceModels.Config config) {
        if (config.rootPath() == null || config.rootPath().isBlank()) throw new IllegalArgumentException("A server folder is required");
        Path requested = Path.of(config.rootPath()).toAbsolutePath().normalize();
        if (allowedRoots.stream().noneMatch(requested::startsWith)) throw new IllegalArgumentException("Folder is outside the configured source roots");
    }
    private Path root(SourceModels.Config config) throws Exception {
        validate(config);
        Path root = Path.of(config.rootPath()).toRealPath();
        boolean allowed = false;
        for (Path candidate : allowedRoots) {
            if (Files.isDirectory(candidate) && root.startsWith(candidate.toRealPath())) allowed = true;
        }
        if (!allowed || !Files.isDirectory(root) || !Files.isReadable(root))
            throw new SourceSupport.SourceProblem("Source folder is unavailable or outside the allowed roots");
        return root;
    }
    @Override public SourceModels.ConnectionTest test(SourceModels.Config config) throws Exception {
        try (var files = Files.newDirectoryStream(root(config))) {
            files.iterator().hasNext();
        }
        return new SourceModels.ConnectionTest(true, "Folder is readable. Paths refer to the Spectra server.", 0);
    }
    @Override public boolean scan(SourceModels.Config config, Sink sink) throws Exception {
        Path root = root(config);
        int[] count = {0};
        boolean[] complete = {true};
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), config.maxDepth() + 1, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws java.io.IOException {
                try { sink.checkCancelled(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
                if (!dir.equals(root) && SourceSupport.excluded(root.relativize(dir).toString(), config)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                try {
                    sink.checkCancelled();
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (!attrs.isRegularFile() || attrs.isSymbolicLink() || !SourceSupport.selected(relative, config)) return FileVisitResult.CONTINUE;
                    if (count[0] >= config.maxDocuments()) { complete[0] = false; return FileVisitResult.TERMINATE; }
                    count[0]++;
                    if (!file.toRealPath().startsWith(root)) throw new SourceSupport.SourceProblem("A source file escaped the allowed folder");
                    if (attrs.size() > config.maxBytes()) throw new SourceSupport.SourceProblem("A source file exceeds the configured size limit");
                    byte[] bytes;
                    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                        bytes = in.readNBytes((int)config.maxBytes() + 1);
                    }
                    if (bytes.length > config.maxBytes()) throw new SourceSupport.SourceProblem("A source file exceeds the configured size limit");
                    var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.lastModifiedTime().equals(after.lastModifiedTime()) || attrs.size() != after.size())
                        throw new SourceSupport.SourceProblem("A source file changed during reading. Retry the synchronization.");
                    sink.accept(new Item(relative, file.getFileName().toString(), null, relative,
                            SourceSupport.hash(bytes), bytes, Map.of("sourceModifiedAt", attrs.lastModifiedTime().toInstant().toString())));
                    return FileVisitResult.CONTINUE;
                } catch (java.io.IOException e) { throw e; }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException(); }
                catch (Exception e) { throw new java.io.IOException("Folder scan interrupted", e); }
            }
        });
        return complete[0];
    }
}
