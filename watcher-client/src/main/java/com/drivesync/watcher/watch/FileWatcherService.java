package com.drivesync.watcher.watch;

import com.drivesync.watcher.hashing.FileHashService;
import com.drivesync.watcher.model.ChangeType;
import com.drivesync.watcher.model.FileChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches one local folder (recursively) for file changes and emits a
 * {@link FileChangeEvent} for every change that is confirmed, via content
 * hashing, to be a REAL content change.
 *
 * ---------------------------------------------------------------------------
 * THE CORE IDEA: don't trust raw filesystem events, trust re-computed state
 * ---------------------------------------------------------------------------
 * Java's WatchService (backed by the OS's native file-change notification
 * API) tells you "something happened at this path" and a rough guess at what
 * kind of thing (create/modify/delete) - but it is notoriously noisy:
 *   - Saving a file in many editors fires several MODIFY events for one save
 *     (write to a temp file, then rename/flush, sometimes touch metadata).
 *   - The event kind isn't always trustworthy across platforms.
 *   - Two rapid edits can coalesce into events that don't cleanly map 1:1
 *     to what actually happened on disk.
 *
 * Rather than trying to interpret every raw event kind precisely, this class
 * uses the raw events only as a signal that "path X might have changed,
 * go take a fresh look" - and DEBOUNCES that signal per-path (waiting for a
 * short quiet period with no further events for that path) before actually
 * re-examining the file. When the quiet period elapses, it reconciles the
 * watcher's last-known-hash for that path against the file's current disk
 * state:
 *   - path no longer exists, but we knew about it   -> DELETED
 *   - path exists, we didn't know about it yet      -> CREATED
 *   - path exists, hash differs from what we knew    -> MODIFIED
 *   - path exists, hash is IDENTICAL to what we knew  -> not a real change,
 *                                                        emit nothing
 * That last case is the whole point of content hashing: a file
 * that was merely touched, or resaved with byte-for-byte identical content,
 * must not trigger a re-sync.
 */
public class FileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);

    private final Path rootFolder;
    private final String deviceId;
    private final FileHashService hashService;
    private final long debounceMillis;
    private final FileChangeListener listener;

    private final WatchService watchService;
    private final Map<WatchKey, Path> watchKeysToDir = new ConcurrentHashMap<>();

    /** relative-path-string -> last known content hash. This IS the change-detection state. */
    private final Map<String, String> knownHashes = new ConcurrentHashMap<>();

    /** One pending debounce task per relative path, so bursts of events collapse into one check. */
    private final Map<String, ScheduledFuture<?>> pendingChecks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService debounceExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "drivesync-watcher-debounce");
                t.setDaemon(true);
                return t;
            });

    private Thread watchLoopThread;
    private volatile boolean running = false;

    public FileWatcherService(Path rootFolder, String deviceId, FileHashService hashService,
                               long debounceMillis, FileChangeListener listener) {
        this.rootFolder = rootFolder;
        this.deviceId = deviceId;
        this.hashService = hashService;
        this.debounceMillis = debounceMillis;
        this.listener = listener;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create a WatchService", e);
        }
    }

    /**
     * Starts watching: scans the current contents of the folder to establish
     * a baseline (and reports every existing file as CREATED, so a folder
     * that already has files in it gets those files synced too - not just
     * ones edited after the watcher starts), then registers watches on every
     * directory and starts the background watch loop.
     */
    public synchronized void start() throws IOException {
        if (running) {
            throw new IllegalStateException("FileWatcherService is already running");
        }
        if (!Files.isDirectory(rootFolder)) {
            throw new IllegalArgumentException("Watch folder does not exist or is not a directory: " + rootFolder);
        }

        performInitialScan();
        registerAll(rootFolder);

        running = true;
        watchLoopThread = new Thread(this::runWatchLoop, "drivesync-watcher-loop");
        watchLoopThread.setDaemon(true);
        watchLoopThread.start();

        log.info("Watching '{}' as device '{}' ({} files known)", rootFolder, deviceId, knownHashes.size());
    }

    public synchronized void stop() {
        running = false;
        if (watchLoopThread != null) {
            watchLoopThread.interrupt();
        }
        debounceExecutor.shutdownNow();
        try {
            watchService.close();
        } catch (IOException ignored) {
            // best-effort on shutdown
        }
    }

    // -------------------------------------------------------------------
    // Initial scan: establishes the baseline hash map and reports existing
    // files as CREATED so they get synced even if never edited afterwards.
    // -------------------------------------------------------------------
    private void performInitialScan() throws IOException {
        Files.walkFileTree(rootFolder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    indexExistingFile(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void indexExistingFile(Path file) {
        try {
            String hash = hashService.sha256(file);
            String relativePath = relativize(file);

            // knownHashes can already contain this path before the initial scan ever
            // runs a single check: WatcherClientApp applies catch-up sync (remote
            // changes fetched from the coordinator) BEFORE starting the watcher, via
            // applyRemoteWrite(), which seeds this same map. Without this check, the
            // scan would re-"discover" a file that only exists locally because it was
            // just written by catch-up, and wrongly report it as a brand new LOCAL
            // change - which would get uploaded right back to the coordinator as if
            // this device had created it. Comparing against any pre-seeded hash first
            // is what keeps "applied a remote change" and "detected a local change"
            // from being conflated.
            String previousHash = knownHashes.get(relativePath);
            if (hash.equals(previousHash)) {
                return;
            }

            knownHashes.put(relativePath, hash);
            ChangeType type = (previousHash == null) ? ChangeType.CREATED : ChangeType.MODIFIED;
            emit(relativePath, type, hash, sizeOf(file));
        } catch (IOException e) {
            log.warn("Could not hash existing file {} during initial scan: {}", file, e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // Registering watches recursively (WatchService is not recursive by itself)
    // -------------------------------------------------------------------
    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        watchKeysToDir.put(key, dir);
    }

    // -------------------------------------------------------------------
    // The watch loop: pulls raw OS events and turns them into debounced
    // "go check this path" signals. Deliberately does NOT act on the raw
    // event kind (see class-level doc) beyond deciding whether a newly
    // created directory needs its own watch registered.
    // -------------------------------------------------------------------
    private void runWatchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take(); // blocks until an event batch is available
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break; // stop() was called
            }

            Path dir = watchKeysToDir.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    // The OS's event buffer overflowed and some events were dropped.
                    // We can't know what we missed, so fall back to re-scanning the
                    // whole subtree to resynchronize our state with disk reality.
                    log.warn("Watch overflow for {}, rescanning subtree", dir);
                    rescan(dir);
                    continue;
                }

                @SuppressWarnings("unchecked")
                Path childName = ((WatchEvent<Path>) event).context();
                Path fullPath = dir.resolve(childName);

                if (kind == ENTRY_CREATE && Files.isDirectory(fullPath, LinkOption.NOFOLLOW_LINKS)) {
                    // A new subdirectory appeared - start watching it too, and treat
                    // any files already inside it (e.g. a folder moved in wholesale)
                    // as new files needing to be indexed.
                    tryRegisterNewDirectory(fullPath);
                    continue;
                }

                scheduleReconcile(fullPath);
            }

            boolean stillValid = key.reset();
            if (!stillValid) {
                watchKeysToDir.remove(key);
            }
        }
    }

    private void tryRegisterNewDirectory(Path newDir) {
        try {
            registerAll(newDir);
            Files.walk(newDir)
                    .filter(Files::isRegularFile)
                    .forEach(this::scheduleReconcile);
        } catch (IOException e) {
            log.warn("Failed to register watch on new directory {}: {}", newDir, e.getMessage());
        }
    }

    private void rescan(Path dir) {
        try {
            Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .forEach(this::scheduleReconcile);
        } catch (IOException e) {
            log.warn("Rescan of {} failed: {}", dir, e.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // Debouncing: collapse a burst of raw events for the same path into a
    // single reconciliation check, run after `debounceMillis` of quiet.
    // -------------------------------------------------------------------
    private void scheduleReconcile(Path file) {
        String relativePath = relativize(file);

        ScheduledFuture<?> existing = pendingChecks.get(relativePath);
        if (existing != null) {
            existing.cancel(false); // a newer event arrived - push the check out again
        }

        ScheduledFuture<?> future = debounceExecutor.schedule(
                () -> reconcile(relativePath, file),
                debounceMillis,
                TimeUnit.MILLISECONDS);
        pendingChecks.put(relativePath, future);
    }

    /**
     * The actual change-detection decision. Runs on the debounce executor
     * thread, once per path, after that path has been quiet for debounceMillis.
     */
    private void reconcile(String relativePath, Path file) {
        pendingChecks.remove(relativePath);
        String previousHash = knownHashes.get(relativePath);

        if (!Files.exists(file)) {
            if (previousHash != null) {
                knownHashes.remove(relativePath);
                emit(relativePath, ChangeType.DELETED, null, -1);
            }
            // else: file was created and deleted again within the debounce window - a no-op.
            return;
        }

        if (!Files.isRegularFile(file)) {
            return; // directories are handled at event-processing time, not here
        }

        try {
            String currentHash = hashService.sha256(file);
            if (currentHash.equals(previousHash)) {
                // This is the crux of content-hash-based change detection: identical
                // bytes, even after a touch/resave, are NOT a change worth syncing.
                log.debug("No content change for {} (hash unchanged), skipping sync", relativePath);
                return;
            }

            knownHashes.put(relativePath, currentHash);
            ChangeType type = (previousHash == null) ? ChangeType.CREATED : ChangeType.MODIFIED;
            emit(relativePath, type, currentHash, sizeOf(file));
        } catch (IOException e) {
            // File likely disappeared or was locked between the exists() check and hashing
            // (e.g. an editor's atomic-save temp-file dance). Safe to skip: if it really
            // changed, the next event for this path will trigger another reconcile.
            log.debug("Could not hash {} during reconcile (probably a transient editor write): {}",
                    relativePath, e.getMessage());
        }
    }

    private void emit(String relativePath, ChangeType type, String hash, long size) {
        FileChangeEvent event = new FileChangeEvent(deviceId, relativePath, type, hash, size, Instant.now());
        log.info("[{}] {} {} {}", deviceId, type, relativePath, hash != null ? "(" + hash.substring(0, 12) + "...)" : "");
        listener.onChange(event);
    }

    private String relativize(Path file) {
        return rootFolder.relativize(file).toString().replace('\\', '/');
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /** Exposed for tests and for milestone-3+ code that needs the current baseline (e.g. catch-up sync). */
    public Map<String, String> knownHashesSnapshot() {
        return Map.copyOf(knownHashes);
    }

    // -------------------------------------------------------------------
    // Applying REMOTE changes (a change that happened on the OTHER device,
    // learned about via WebSocket push or catch-up fetch - milestone 4).
    //
    // THE ECHO PROBLEM AND HOW THIS AVOIDS IT: writing a remote change to
    // disk here will still fire a normal OS-level WatchService event for
    // that path, exactly as if a human had edited the file - there is no
    // OS-level way to write a file "silently". Without care, that would
    // cause reconcile() to treat our own remote-applied write as a brand
    // new LOCAL change and submit it right back to the coordinator, which
    // would broadcast it back out again - an infinite loop.
    //
    // The fix falls directly out of this class's core design: reconcile()
    // already treats "hash matches knownHashes" as "not a change, skip".
    // So applyRemoteWrite/applyRemoteDelete just need to update knownHashes
    // to match the new reality BEFORE (or immediately after) writing to
    // disk. When the debounced reconcile() eventually runs for this path,
    // it hashes the file, finds that hash already in knownHashes, and
    // silently does nothing - no new FileChangeEvent, no re-submission.
    // No separate "ignore this event" flag or suppression list is needed.
    // -------------------------------------------------------------------

    /** Writes remote content to disk and marks it as already-known, so it is not re-reported as a local change. */
    public synchronized void applyRemoteWrite(String relativePath, byte[] content, String hash) throws IOException {
        Path target = rootFolder.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        knownHashes.put(relativePath, hash);
        log.info("Applied remote write for '{}' ({} bytes)", relativePath, content.length);
    }

    /** Deletes a file to reflect a remote deletion, and forgets it so a future recreation is seen as CREATED. */
    public synchronized void applyRemoteDelete(String relativePath) throws IOException {
        Path target = rootFolder.resolve(relativePath);
        Files.deleteIfExists(target);
        knownHashes.remove(relativePath);
        log.info("Applied remote delete for '{}'", relativePath);
    }

    /**
     * True if this path's currently-known hash already matches, i.e. applying
     * this content would be a no-op. This is a CONTENT-based echo check, and
     * it's the correct one to use for deciding whether an incoming remote
     * change needs to be applied - deviceId-based filtering ("skip if this
     * change came from my own deviceId") looks similar but is actually wrong
     * in one important case (see RemoteChangeApplier): a conflict-resolution
     * record can legitimately be attributed to MY OWN deviceId while
     * describing a NEW path I don't have yet (my own older content, now
     * being relocated to a "conflicted copy" name by the server on my
     * behalf) - deviceId-based filtering would wrongly treat that as my own
     * echo and skip it, silently failing to create the backup file. Checking
     * the actual known hash at the actual target path has no such blind
     * spot: it only says "skip" when this exact content is already exactly
     * where it needs to be, regardless of whose deviceId is attached.
     */
    public boolean alreadyHasContent(String relativePath, String hash) {
        return hash != null && hash.equals(knownHashes.get(relativePath));
    }

    /** True if this path is not currently known to exist locally - i.e. a remote delete for it would be a no-op. */
    public boolean alreadyAbsent(String relativePath) {
        return !knownHashes.containsKey(relativePath);
    }
}
