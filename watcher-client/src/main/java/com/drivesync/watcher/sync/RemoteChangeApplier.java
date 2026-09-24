package com.drivesync.watcher.sync;

import com.drivesync.watcher.model.ChangeType;
import com.drivesync.watcher.watch.FileWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Applies a change that happened on ANOTHER device to this device's local
 * folder - the other half of sync, alongside LocalChangeUploader. Used for
 * both live WebSocket-pushed changes and REST catch-up results, since both
 * arrive as the same ChangeDto shape.
 */
public class RemoteChangeApplier {

    private static final Logger log = LoggerFactory.getLogger(RemoteChangeApplier.class);

    private final SyncApiClient apiClient;
    private final FileWatcherService watcherService;
    private final PathVersionTracker versionTracker;

    public RemoteChangeApplier(SyncApiClient apiClient, FileWatcherService watcherService,
                                PathVersionTracker versionTracker) {
        this.apiClient = apiClient;
        this.watcherService = watcherService;
        this.versionTracker = versionTracker;
    }

    public void apply(ChangeDto change) {
        // CONTENT-based echo check, not deviceId-based: "do I already have exactly this
        // content at exactly this path?" A deviceId check ("did I make this change?") looks
        // equivalent but is NOT, for one important case introduced by conflict resolution
        // (milestone 5): the server can attribute a synthetic "conflicted copy" record to
        // MY OWN deviceId while describing a NEW path I don't have yet (my own older content
        // being relocated there on my behalf after losing a conflict I never actively
        // submitted anything for). A deviceId check would wrongly skip that as "my echo" and
        // silently never create the backup file - see LEARNING.md for how this was found.
        // The content check has no such blind spot: it only says "nothing to do" when the
        // exact bytes are already exactly where they need to be.
        boolean noOp = change.changeType() == ChangeType.DELETED
                ? watcherService.alreadyAbsent(change.relativePath())
                : watcherService.alreadyHasContent(change.relativePath(), change.contentHash());

        if (noOp) {
            log.debug("Already up to date for '{}', nothing to apply", change.relativePath());
            versionTracker.record(change.relativePath(), change.id());
            return;
        }

        try {
            if (change.changeType() == ChangeType.DELETED) {
                watcherService.applyRemoteDelete(change.relativePath());
            } else {
                byte[] content = apiClient.downloadBlob(change.contentHash());
                watcherService.applyRemoteWrite(change.relativePath(), content, change.contentHash());
            }
            versionTracker.record(change.relativePath(), change.id());
            log.info("Applied remote {} for '{}' from device '{}'",
                    change.changeType(), change.relativePath(), change.deviceId());
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to apply remote change for '{}' from '{}': {}",
                    change.relativePath(), change.deviceId(), e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
