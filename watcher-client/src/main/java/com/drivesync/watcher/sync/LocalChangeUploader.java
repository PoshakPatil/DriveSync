package com.drivesync.watcher.sync;

import com.drivesync.watcher.model.ChangeType;
import com.drivesync.watcher.model.FileChangeEvent;
import com.drivesync.watcher.watch.FileChangeListener;
import com.drivesync.watcher.watch.FileWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The FileChangeListener that turns "we detected a local change" (milestone 2)
 * into "the coordinator now knows about it" (milestone 3/4), and handles
 * being told our own change LOST a conflict (milestone 5).
 *
 * Two network calls per real change, and the ORDER between them matters:
 *   1. PUT the file's bytes to the blob store first (skipped for DELETED,
 *      and skipped if the server already has a blob with this hash).
 *   2. THEN POST the change metadata to /api/sync/changes.
 *
 * Content is uploaded before the change is announced, not after, because
 * step 2 is what causes the coordinator to broadcast this change to every
 * other connected device (see ChangeBroadcastService server-side) - if the
 * order were reversed, another device could receive the "greeting.txt
 * changed, hash X" notification and immediately try to download blob X
 * before this device had finished uploading it, getting a 404. Uploading
 * first makes that race impossible: by the time anyone else can possibly
 * hear about this change, the content it refers to already exists.
 */
public class LocalChangeUploader implements FileChangeListener {

    private static final Logger log = LoggerFactory.getLogger(LocalChangeUploader.class);

    private final SyncApiClient apiClient;
    private final Path watchFolder;
    private final PathVersionTracker versionTracker;

    // Set after construction via attachWatcherService() - FileWatcherService's constructor
    // requires a FileChangeListener (this class), so the two can't both be passed to each
    // other's constructor. See WatcherClientApp for the wiring order.
    private FileWatcherService watcherService;

    public LocalChangeUploader(SyncApiClient apiClient, Path watchFolder, PathVersionTracker versionTracker) {
        this.apiClient = apiClient;
        this.watchFolder = watchFolder;
        this.versionTracker = versionTracker;
    }

    public void attachWatcherService(FileWatcherService watcherService) {
        this.watcherService = watcherService;
    }

    @Override
    public void onChange(FileChangeEvent event) {
        try {
            if (event.changeType() != ChangeType.DELETED) {
                uploadContentIfNeeded(event);
            }

            Long baseChangeId = versionTracker.get(event.relativePath());
            ChangeDto response = apiClient.submitChange(event, baseChangeId);

            // A conflict response with a non-null conflictedCopyPath means OUR submission
            // specifically was the LOSING side (see ChangeResponse's javadoc server-side).
            // A conflict can also resolve in our favor (our edit was the newer one) - in
            // that case conflictedCopyPath is null, and from our own local disk's point of
            // view nothing needs to change: our file already has the right content, we just
            // record the version like any normal accepted change.
            if (response.conflict() && response.conflictedCopyPath() != null) {
                handleOwnConflict(event, response);
            } else {
                versionTracker.record(event.relativePath(), response.id());
            }
        } catch (IOException | InterruptedException e) {
            // A single failed submission (e.g. the coordinator is briefly unreachable)
            // shouldn't crash the watcher - the file itself is unaffected, and if the
            // coordinator becomes reachable again this device's OWN changes just live
            // only in its local hash cache. Full retry/outbox durability for submission
            // failures is a reasonable follow-up but out of scope for v1.
            log.warn("Failed to sync change for '{}': {}", event.relativePath(), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Our own submission lost a conflict: an independent edit made elsewhere,
     * based on the same stale knowledge we were building on, turned out to be
     * newer than ours. Two corrective writes, both routed through
     * FileWatcherService's "remote apply" path (not a plain file move) so
     * neither is mistaken for a brand new local edit and re-submitted:
     *   1. Preserve OUR content under the conflicted-copy name - it's never
     *      discarded, just renamed.
     *   2. Restore the WINNING content at the original path, so our local
     *      folder converges to the same truth as every other device's.
     * Order matters here too: save our own content first, so there's never a
     * moment where it exists nowhere on disk.
     */
    private void handleOwnConflict(FileChangeEvent event, ChangeDto response) throws IOException, InterruptedException {
        Path original = watchFolder.resolve(event.relativePath());
        if (Files.exists(original)) {
            byte[] ourContent = Files.readAllBytes(original);
            watcherService.applyRemoteWrite(response.conflictedCopyPath(), ourContent, event.contentHash());
            versionTracker.record(response.conflictedCopyPath(), response.conflictedCopyChangeId());
        }

        byte[] winningContent = apiClient.downloadBlob(response.winningContentHash());
        watcherService.applyRemoteWrite(event.relativePath(), winningContent, response.winningContentHash());
        versionTracker.record(event.relativePath(), response.id());

        log.warn("CONFLICT on '{}': an independent, newer edit exists elsewhere. Your version was kept as '{}'.",
                event.relativePath(), response.conflictedCopyPath());
    }

    private void uploadContentIfNeeded(FileChangeEvent event) throws IOException, InterruptedException {
        String hash = event.contentHash();
        if (apiClient.blobExists(hash)) {
            log.debug("Blob {} already present server-side, skipping upload for '{}'", hash, event.relativePath());
            return;
        }

        Path file = watchFolder.resolve(event.relativePath());
        if (!Files.exists(file)) {
            // The file changed again (or was deleted) between detection and upload -
            // the next debounced reconcile will pick up whatever the current truth is.
            log.debug("'{}' no longer exists at upload time, skipping this upload", event.relativePath());
            return;
        }

        byte[] content = Files.readAllBytes(file);
        apiClient.uploadBlob(hash, content);
        log.info("Uploaded content for '{}' ({} bytes, hash {}...)", event.relativePath(), content.length,
                hash.substring(0, 12));
    }
}
