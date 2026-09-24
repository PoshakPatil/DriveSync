package com.drivesync.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A detected conflict: two devices independently changed the same file path
 * based on divergent knowledge of its history (see ConflictResolver for how
 * this is detected). Both versions are always preserved - one keeps the
 * original path, the other lives on at conflictedCopyPath - so this entity
 * exists purely to surface the situation (dashboard, milestone 6) and let a
 * user override the automatic choice of which side kept the original name.
 */
@Entity
@Table(name = "conflicts")
public class Conflict {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Column(name = "conflicted_copy_path", nullable = false)
    private String conflictedCopyPath;

    @Column(name = "winning_device_id", nullable = false)
    private String winningDeviceId;

    @Column(name = "winning_hash", nullable = false)
    private String winningHash;

    @Column(name = "winning_size_bytes", nullable = false)
    private long winningSizeBytes;

    @Column(name = "winning_change_id", nullable = false)
    private Long winningChangeId;

    @Column(name = "losing_device_id", nullable = false)
    private String losingDeviceId;

    @Column(name = "losing_hash", nullable = false)
    private String losingHash;

    @Column(name = "losing_size_bytes", nullable = false)
    private long losingSizeBytes;

    @Column(name = "losing_change_id", nullable = false)
    private Long losingChangeId;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_keep_device_id")
    private String resolvedKeepDeviceId;

    protected Conflict() {
        // required by JPA
    }

    public Conflict(String relativePath, String conflictedCopyPath,
                     String winningDeviceId, String winningHash, long winningSizeBytes, Long winningChangeId,
                     String losingDeviceId, String losingHash, long losingSizeBytes, Long losingChangeId,
                     Instant detectedAt) {
        this.relativePath = relativePath;
        this.conflictedCopyPath = conflictedCopyPath;
        this.winningDeviceId = winningDeviceId;
        this.winningHash = winningHash;
        this.winningSizeBytes = winningSizeBytes;
        this.winningChangeId = winningChangeId;
        this.losingDeviceId = losingDeviceId;
        this.losingHash = losingHash;
        this.losingSizeBytes = losingSizeBytes;
        this.losingChangeId = losingChangeId;
        this.detectedAt = detectedAt;
        this.resolved = false;
    }

    public void markResolved(String keepDeviceId, Instant now) {
        this.resolved = true;
        this.resolvedKeepDeviceId = keepDeviceId;
        this.resolvedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getConflictedCopyPath() {
        return conflictedCopyPath;
    }

    public String getWinningDeviceId() {
        return winningDeviceId;
    }

    public String getWinningHash() {
        return winningHash;
    }

    public long getWinningSizeBytes() {
        return winningSizeBytes;
    }

    public Long getWinningChangeId() {
        return winningChangeId;
    }

    public String getLosingDeviceId() {
        return losingDeviceId;
    }

    public String getLosingHash() {
        return losingHash;
    }

    public long getLosingSizeBytes() {
        return losingSizeBytes;
    }

    public Long getLosingChangeId() {
        return losingChangeId;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public boolean isResolved() {
        return resolved;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolvedKeepDeviceId() {
        return resolvedKeepDeviceId;
    }
}
