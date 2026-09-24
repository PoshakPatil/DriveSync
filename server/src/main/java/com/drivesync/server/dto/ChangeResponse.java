package com.drivesync.server.dto;

import com.drivesync.server.model.ChangeType;
import com.drivesync.server.model.FileChangeRecord;

import java.time.Instant;

/**
 * @param duplicate            true if the server determined this submission was a no-op
 *                              (submitted hash matched the server's already-known current
 *                              hash for the path) and therefore did NOT create a new log
 *                              entry - `id` in that case refers to the prior record.
 * @param conflict              true if this submission was found to conflict with an
 *                              independent change already on the server (see ConflictResolver).
 *                              `id` in that case is the WINNING change's id (the content now
 *                              authoritative at `relativePath`) - not necessarily this submission's own.
 * @param conflictedCopyPath   set only when `conflict` is true AND this submission was the
 *                              LOSING side: the path the submitter's own (losing) content was
 *                              preserved at, so the client knows where to move it locally.
 * @param winningContentHash   set only when `conflict` is true AND this submission was the
 *                              LOSING side: the hash of the content the submitter should
 *                              download and restore at the original path.
 * @param conflictedCopyChangeId set only when `conflict` is true: the change id assigned to the
 *                                conflicted-copy record, so the submitter can track it going forward.
 */
public record ChangeResponse(
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
    public static ChangeResponse from(FileChangeRecord record, boolean duplicate) {
        return new ChangeResponse(record.getId(), record.getDeviceId(), record.getRelativePath(),
                record.getChangeType(), record.getContentHash(), record.getSizeBytes(),
                record.getClientDetectedAt(), record.getServerReceivedAt(), duplicate,
                false, null, null, null);
    }
}
