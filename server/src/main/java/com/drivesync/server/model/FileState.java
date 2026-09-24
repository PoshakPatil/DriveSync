package com.drivesync.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * The coordinator's current best understanding of "what does this file look
 * like right now", one row per relative path, derived from (and always kept
 * in sync with) the FileChangeRecord log.
 *
 * Two jobs:
 *   1. Server-side dedup: when a change comes in, comparing its hash against
 *      currentHash here is what lets the server (not just the watcher) skip
 *      recording a no-op "change" - defense in depth alongside the watcher's
 *      own client-side hash check.
 *   2. The foundation conflict detection (milestone 5) builds on: knowing
 *      "what the server currently believes about this path, and which
 *      change last updated it" is exactly what's needed to recognize when
 *      an incoming change is based on stale/divergent knowledge.
 */
@Entity
@Table(name = "file_states")
public class FileState {

    @Id
    @Column(name = "relative_path", nullable = false, updatable = false)
    private String relativePath;

    @Column(name = "current_hash")
    private String currentHash;

    @Column(name = "current_size_bytes")
    private long currentSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_change_type", nullable = false)
    private ChangeType lastChangeType;

    @Column(name = "last_changed_by_device", nullable = false)
    private String lastChangedByDevice;

    @Column(name = "last_change_id", nullable = false)
    private Long lastChangeId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FileState() {
        // required by JPA
    }

    public FileState(String relativePath) {
        this.relativePath = relativePath;
    }

    public void apply(FileChangeRecord change) {
        this.currentHash = change.getContentHash();
        this.currentSizeBytes = change.getSizeBytes();
        this.lastChangeType = change.getChangeType();
        this.lastChangedByDevice = change.getDeviceId();
        this.lastChangeId = change.getId();
        this.updatedAt = change.getServerReceivedAt();
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getCurrentHash() {
        return currentHash;
    }

    public long getCurrentSizeBytes() {
        return currentSizeBytes;
    }

    public ChangeType getLastChangeType() {
        return lastChangeType;
    }

    public String getLastChangedByDevice() {
        return lastChangedByDevice;
    }

    public Long getLastChangeId() {
        return lastChangeId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
