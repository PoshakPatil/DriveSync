package com.drivesync.watcher.model;

import java.time.Instant;

/**
 * A single detected, confirmed change to one file in the watched folder.
 *
 * "Confirmed" matters: this is only ever created AFTER the watcher has
 * debounced rapid-fire filesystem events and re-hashed the file, so by the
 * time this object exists we know the content genuinely differs from what
 * we last saw (see FileHashService / FileWatcherService for how).
 *
 * @param deviceId     which device produced this change (this watcher instance)
 * @param relativePath path of the file relative to the watched folder root,
 *                     using forward slashes so it's stable across OSes
 * @param changeType   CREATED / MODIFIED / DELETED
 * @param contentHash  SHA-256 hex digest of the new content, or null for DELETED
 * @param sizeBytes    file size in bytes, or -1 for DELETED
 * @param detectedAt   when the watcher confirmed this change (client clock)
 */
public record FileChangeEvent(
        String deviceId,
        String relativePath,
        ChangeType changeType,
        String contentHash,
        long sizeBytes,
        Instant detectedAt
) {
}
