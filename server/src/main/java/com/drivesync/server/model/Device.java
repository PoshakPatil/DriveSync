package com.drivesync.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A device known to the coordinator (one of the (up to two, for v1) laptops
 * running a watcher instance). Devices self-register the first time they
 * connect; there's no separate admin-driven provisioning step.
 *
 * "online" is maintained by the WebSocket layer (milestone 4) based on
 * whether the device currently has a live session connected - registering
 * over REST alone does not mean a device is online, only that it exists and
 * was last seen at some point.
 */
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @Column(name = "device_id", nullable = false, updatable = false)
    private String deviceId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "online", nullable = false)
    private boolean online;

    protected Device() {
        // required by JPA
    }

    public Device(String deviceId, String displayName, Instant now) {
        this.deviceId = deviceId;
        this.displayName = displayName;
        this.firstSeenAt = now;
        this.lastSeenAt = now;
        this.online = false;
    }

    public void touch(String displayName, Instant now) {
        if (displayName != null && !displayName.isBlank()) {
            this.displayName = displayName;
        }
        this.lastSeenAt = now;
    }

    public void setOnline(boolean online, Instant now) {
        this.online = online;
        this.lastSeenAt = now;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public boolean isOnline() {
        return online;
    }
}
