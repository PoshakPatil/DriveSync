package com.drivesync.watcher.sync;

import com.drivesync.watcher.model.ChangeType;

import java.time.Instant;

/**
 * The wire shape of a change as the coordinator server describes it - matches
 * server's ChangeResponse field-for-field. Used both for deserializing
 * catch-up fetch results (REST) and live-pushed messages (WebSocket/STOMP) -
 * same JSON shape either way, since the server broadcasts the exact same
 * object it returns from a REST submission.
 */
public record ChangeDto(
        Long id,
        String deviceId,
        String relativePath,
        ChangeType changeType,
        String contentHash,
        long sizeBytes,
        Instant clientDetectedAt,
        Instant serverReceivedAt,
        boolean duplicate,
        boolean conflict,
        String conflictedCopyPath,
        String winningContentHash,
        Long conflictedCopyChangeId
) {
}
