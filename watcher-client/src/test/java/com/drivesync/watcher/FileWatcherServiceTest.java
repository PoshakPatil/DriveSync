package com.drivesync.watcher;

import com.drivesync.watcher.hashing.FileHashService;
import com.drivesync.watcher.model.ChangeType;
import com.drivesync.watcher.model.FileChangeEvent;
import com.drivesync.watcher.watch.FileWatcherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the actual change-detection behavior end to end (real filesystem,
 * real WatchService) rather than mocking, since the whole point of this class
 * is its interaction with the OS filesystem watch API - a mock would just
 * test that we call methods we wrote ourselves.
 */
class FileWatcherServiceTest {

    // Debounce kept short so tests don't need long sleeps, but long enough that
    // a burst of writes to the same path still collapses into one reconcile.
    private static final long DEBOUNCE_MS = 200;
    private static final long WAIT_MS = DEBOUNCE_MS + 800;

    @TempDir
    Path tempDir;

    private List<FileChangeEvent> events;
    private FileWatcherService watcher;

    @BeforeEach
    void setUp() throws IOException {
        events = new CopyOnWriteArrayList<>();
        watcher = new FileWatcherService(tempDir, "test-device", new FileHashService(),
                DEBOUNCE_MS, events::add);
        watcher.start();
    }

    @AfterEach
    void tearDown() {
        watcher.stop();
    }

    @Test
    void detectsNewFileAsCreated() throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello", StandardCharsets.UTF_8);

        FileChangeEvent event = awaitEventFor("a.txt", WAIT_MS);
        assertEquals(ChangeType.CREATED, event.changeType());
        assertNotNull(event.contentHash());
    }

    @Test
    void detectsRealContentChangeAsModified() throws Exception {
        Path file = tempDir.resolve("b.txt");
        Files.writeString(file, "version one", StandardCharsets.UTF_8);
        awaitEventFor("b.txt", WAIT_MS);
        events.clear();

        Files.writeString(file, "version two - actually different", StandardCharsets.UTF_8);

        FileChangeEvent event = awaitEventFor("b.txt", WAIT_MS);
        assertEquals(ChangeType.MODIFIED, event.changeType());
    }

    @Test
    void rewritingIdenticalContentDoesNotTriggerAnEvent() throws Exception {
        Path file = tempDir.resolve("c.txt");
        String content = "unchanging content";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        awaitEventFor("c.txt", WAIT_MS);
        events.clear();

        // Rewrite with byte-for-byte identical content - simulates an editor
        // re-saving without real edits, or a touch that updates mtime only.
        Files.writeString(file, content, StandardCharsets.UTF_8);
        Thread.sleep(WAIT_MS);

        assertTrue(events.isEmpty(),
                "Identical content must not produce a change event, per content-hash-based detection");
    }

    @Test
    void detectsDeletion() throws Exception {
        Path file = tempDir.resolve("d.txt");
        Files.writeString(file, "will be deleted", StandardCharsets.UTF_8);
        awaitEventFor("d.txt", WAIT_MS);
        events.clear();

        Files.delete(file);

        FileChangeEvent event = awaitEventFor("d.txt", WAIT_MS);
        assertEquals(ChangeType.DELETED, event.changeType());
        assertNull(event.contentHash());
    }

    private FileChangeEvent awaitEventFor(String relativePath, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (FileChangeEvent e : events) {
                if (e.relativePath().equals(relativePath)) {
                    return e;
                }
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for a change event on " + relativePath + ", got: " + events);
        return null; // unreachable
    }
}
