package com.drivesync.server.dto;

import com.drivesync.server.model.ChangeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * What a watcher client POSTs to /api/sync/changes for one confirmed local file change.
 *
 * @param baseChangeId the server-assigned change id this device believes is
 *                      CURRENTLY authoritative for this path - i.e. the id of
 *                      the last change it applied or made for this path, or
 *                      null if it has never seen any change for this path.
 *                      This is what makes conflict detection possible: see
 *                      ConflictResolver for how a mismatch here (combined
 *                      with genuinely different content) is what distinguishes
 *                      "an independent, conflicting edit" from "an edit that's
 *                      simply caught up on everything so far."
 */
public record ChangeSubmissionRequest(
        @NotBlank String deviceId,
        @NotBlank String relativePath,
        @NotNull ChangeType changeType,
        String contentHash,
        long sizeBytes,
        @NotNull Instant clientDetectedAt,
        Long baseChangeId
) {
}
