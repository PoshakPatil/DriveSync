package com.drivesync.server.ws;

import com.drivesync.server.dto.ChangeResponse;
import com.drivesync.server.dto.ConflictResponse;
import com.drivesync.server.dto.DeviceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * The "push" half of real-time sync: wraps Spring's SimpMessagingTemplate so
 * the rest of the app (SyncCoordinatorService, PresenceEventListener) can
 * broadcast without knowing anything about STOMP destinations directly.
 *
 * Note this broadcasts to EVERY subscriber, including the device that made
 * the change in the first place - there's no "exclude the sender" at the
 * broker level with a simple topic broker. Instead, receiving clients are
 * expected to compare the change's deviceId against their own and ignore
 * their own echoes (see watcher-client's RemoteChangeApplier). This keeps
 * the server side simple and stateless per-broadcast.
 */
@Service
public class ChangeBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(ChangeBroadcastService.class);

    private static final String CHANGES_TOPIC = "/topic/changes";
    private static final String DEVICES_TOPIC = "/topic/devices";
    private static final String CONFLICTS_TOPIC = "/topic/conflicts";

    private final SimpMessagingTemplate messagingTemplate;

    public ChangeBroadcastService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastChange(ChangeResponse change) {
        log.debug("Broadcasting change {} on '{}' to {}", change.id(), change.relativePath(), CHANGES_TOPIC);
        messagingTemplate.convertAndSend(CHANGES_TOPIC, change);
    }

    public void broadcastDeviceStatus(DeviceResponse device) {
        log.debug("Broadcasting device status for '{}' to {}", device.deviceId(), DEVICES_TOPIC);
        messagingTemplate.convertAndSend(DEVICES_TOPIC, device);
    }

    public void broadcastConflict(ConflictResponse conflict) {
        log.debug("Broadcasting conflict {} on '{}' to {}", conflict.id(), conflict.relativePath(), CONFLICTS_TOPIC);
        messagingTemplate.convertAndSend(CONFLICTS_TOPIC, conflict);
    }
}
