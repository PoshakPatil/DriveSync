package com.drivesync.server.service;

import com.drivesync.server.model.Device;
import com.drivesync.server.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /** Idempotent: a device calls this every time it starts up, whether it's brand new or returning. */
    public Device registerOrTouch(String deviceId, String displayName) {
        Instant now = Instant.now();
        return deviceRepository.findById(deviceId)
                .map(existing -> {
                    existing.touch(displayName, now);
                    return existing;
                })
                .orElseGet(() -> deviceRepository.save(
                        new Device(deviceId, displayName != null && !displayName.isBlank() ? displayName : deviceId, now)));
    }

    public List<Device> listAll() {
        return deviceRepository.findAll();
    }

    /**
     * Marks a device online/offline, driven by WebSocket session lifecycle
     * events (see PresenceEventListener) rather than by REST activity alone -
     * a device can be "known" (registered) without currently being connected.
     * Upserts defensively in case a WS event somehow arrives before the
     * device's own REST registration call completes.
     */
    public Device setOnline(String deviceId, boolean online) {
        Instant now = Instant.now();
        Device device = deviceRepository.findById(deviceId)
                .orElseGet(() -> new Device(deviceId, deviceId, now));
        device.setOnline(online, now);
        return deviceRepository.save(device);
    }
}
