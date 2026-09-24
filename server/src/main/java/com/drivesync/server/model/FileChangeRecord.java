package com.drivesync.server.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * An append-only log entry: "device X reported that path Y changed in this
 * way at this time." This table is never updated or deleted from - it is
 * both:
 *   1. the source of truth for the activity feed (milestone 6), and
 *   2. the mechanism for catch-up sync: a reconnecting device asks for
 *      every record with id > (the last id it saw), which works because
 *      Postgres's auto-incrementing id column is naturally a monotonically
 *      increasing "version number" for the whole change stream - no need
 *      for a separate sequence/versioning scheme.
 *
 * Contrast this with FileState, which is a mutable "latest known state per
 * path" table derived FROM this log.
 */
@Entity
@Table(name = "file_change_records")
public class FileChangeRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "relative_path", nullable = false)
    private String relativePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false)
    private ChangeType changeType;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "size_bytes")
    private long sizeBytes;

    @Column(name = "client_detected_at", nullable = false)
    private Instant clientDetectedAt;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    protected FileChangeRecord() {
        // required by JPA
    }

    public FileChangeRecord(String deviceId, String relativePath, ChangeType changeType,
                             String contentHash, long sizeBytes, Instant clientDetectedAt,
                             Instant serverReceivedAt) {
        this.deviceId = deviceId;
        this.relativePath = relativePath;
        this.changeType = changeType;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.clientDetectedAt = clientDetectedAt;
        this.serverReceivedAt = serverReceivedAt;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getClientDetectedAt() {
        return clientDetectedAt;
    }

    public Instant getServerReceivedAt() {
        return serverReceivedAt;
    }
}
