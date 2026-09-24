package com.drivesync.server.dto;

import com.drivesync.server.model.Conflict;

import java.time.Instant;

public record ConflictResponse(
        Long id,
        String relativePath,
        String conflictedCopyPath,
        String winningDeviceId,
        String winningHash,
        Long winningChangeId,
        String losingDeviceId,
        String losingHash,
        Long losingChangeId,
        Instant detectedAt,
        boolean resolved,
        Instant resolvedAt,
        String resolvedKeepDeviceId
) {
    public static ConflictResponse from(Conflict c) {
        return new ConflictResponse(c.getId(), c.getRelativePath(), c.getConflictedCopyPath(),
                c.getWinningDeviceId(), c.getWinningHash(), c.getWinningChangeId(),
                c.getLosingDeviceId(), c.getLosingHash(), c.getLosingChangeId(),
                c.getDetectedAt(), c.isResolved(), c.getResolvedAt(), c.getResolvedKeepDeviceId());
    }
}
