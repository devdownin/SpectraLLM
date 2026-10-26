package fr.spectra.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Durable, fail-closed barrier for in-place embedding migrations (single JVM, persistent volume). */
@Service
public class CollectionMaintenanceGuard {
    private final Path directory;
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    private final Map<String, String> namesById = new ConcurrentHashMap<>();

    @Autowired
    public CollectionMaintenanceGuard(@Value("${spectra.chromadb.maintenance-dir:./data/collection-maintenance}") String directory) {
        this(Path.of(directory));
    }

    CollectionMaintenanceGuard(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".properties")).toList()) {
                    Properties properties = new Properties();
                    try (Reader reader = Files.newBufferedReader(file)) { properties.load(reader); }
                    String name = properties.getProperty("collection");
                    String id = properties.getProperty("id");
                    if (name == null || id == null || !marker(name).equals(file)) {
                        throw new IOException("Invalid maintenance marker: " + file.getFileName());
                    }
                    namesById.put(id, name);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load collection maintenance state", e);
        }
    }

    private ReentrantReadWriteLock lock(String collection) {
        return locks.computeIfAbsent(collection, ignored -> new ReentrantReadWriteLock(true));
    }

    public void register(String collection, String id) { namesById.put(id, collection); }

    public Lease acquireId(String id) {
        String name = namesById.get(id);
        return name == null ? Lease.NONE : acquire(name);
    }

    public Lease acquire(String collection) {
        var read = lock(collection).readLock();
        // Hybrid retrieval holds a parent lease while child executor threads acquire theirs.
        // Barging readers must be admitted while a writer is only queued; otherwise the
        // parent waits for children which wait behind the writer waiting for the parent.
        if (!read.tryLock()) read.lock();
        try {
            assertAvailable(collection);
            return new Lease(read::unlock);
        } catch (RuntimeException e) {
            read.unlock();
            throw e;
        }
    }

    public void assertAvailable(String collection) {
        try {
            Files.readAttributes(marker(collection), BasicFileAttributes.class);
        } catch (NoSuchFileException absent) {
            return;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot verify maintenance state for " + collection, e);
        }
        throw new IllegalStateException("Collection '" + collection
                + "' indisponible : ré-indexation incomplète ; relancez la ré-indexation pour reprendre.");
    }

    /** Wait for admitted reads/writes, then durably block all subsequent operations. */
    public void begin(String collection, String id) {
        var write = lock(collection).writeLock();
        write.lock();
        Path temporary = null;
        try {
            register(collection, id);
            temporary = Files.createTempFile(directory, "migration-", ".tmp");
            Properties state = new Properties();
            state.setProperty("collection", collection);
            state.setProperty("id", id);
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                state.store(writer, "Incomplete embedding migration; only a successful full rerun may remove this marker");
            }
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
            Files.move(temporary, marker(collection), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist collection maintenance state for " + collection, e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            write.unlock();
        }
    }

    /** Call only after every vector and the final embedding stamp have been written successfully. */
    public void complete(String collection) {
        var write = lock(collection).writeLock();
        write.lock();
        try {
            Files.delete(marker(collection));
            forceDirectory();
        } catch (IOException e) {
            // If a directory fsync fails after deletion, reinstate a durable marker where possible.
            String id = namesById.entrySet().stream().filter(e2 -> collection.equals(e2.getValue()))
                    .map(Map.Entry::getKey).findFirst().orElse(collection);
            begin(collection, id);
            throw new IllegalStateException("Cannot complete maintenance state for " + collection, e);
        } finally {
            write.unlock();
        }
    }

    private void forceDirectory() throws IOException {
        try (var directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) { directoryChannel.force(true); }
    }

    private Path marker(String collection) {
        try {
            String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(collection.getBytes(StandardCharsets.UTF_8)));
            return directory.resolve(key + ".properties");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class Lease implements AutoCloseable {
        public static final Lease NONE = new Lease(() -> { });
        private Runnable release;
        private Lease(Runnable release) { this.release = release; }
        @Override public void close() {
            Runnable action = release;
            release = () -> { };
            action.run();
        }
    }
}
