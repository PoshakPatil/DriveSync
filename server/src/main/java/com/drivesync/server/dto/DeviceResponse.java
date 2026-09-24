package com.drivesync.server.dto;

import com.drivesync.server.model.Device;

import java.time.Instant;

public record DeviceResponse(
        String deviceId,
        String displayName,
        Instant firstSeenAt,
        Instant lastSeenAt,
        boolean online
) {
    public static DeviceResponse from(Device device) {
        return new DeviceResponse(device.getDeviceId(), device.getDisplayName(),
                device.getFirstSeenAt(), device.getLastSeenAt(), device.isOnline());
    }
}
