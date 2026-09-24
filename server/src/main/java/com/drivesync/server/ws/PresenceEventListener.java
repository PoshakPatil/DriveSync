package com.drivesync.server.ws;

import com.drivesync.server.dto.DeviceResponse;
import com.drivesync.server.model.Device;
import com.drivesync.server.service.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * Translates raw WebSocket/STOMP connect and disconnect events into device
 * presence updates. This is what makes the dashboard's "online/offline"
 * status (milestone 6) actually live, rather than a fixed value only REST
 * activity could update.
 *
 * The deviceId isn't in the STOMP frame headers here - it rides along as a
 * WebSocket session attribute set during the handshake (see
 * DeviceIdHandshakeInterceptor), which Spring's STOMP support copies into
 * each subsequent message's "simpSessionAttributes" for the life of that
 * session.
 */
@Component
public class PresenceEventListener {

    private static final Logger log = LoggerFactory.getLogger(PresenceEventListener.class);

    private final DeviceService deviceService;
    private final ChangeBroadcastService broadcastService;

    public PresenceEventListener(DeviceService deviceService, ChangeBroadcastService broadcastService) {
        this.deviceService = deviceService;
        this.broadcastService = broadcastService;
    }

    // Deliberately SessionConnectEvent (fired when the CONNECT frame arrives), not
    // SessionConnectedEvent (fired when the CONNECTED reply is sent back out): only
    // the former reliably carries the original handshake session attributes through
    // StompHeaderAccessor in this version of Spring's STOMP support. This was found
    // by testing - SessionConnectedEvent alone left every device stuck "offline"
    // even while genuinely connected, while SessionDisconnectEvent (used below)
    // worked correctly with the same accessor pattern, confirming the attribute
    // plumbing itself was fine and the event type was the actual problem.
    @EventListener
    public void onConnected(SessionConnectEvent event) {
        deviceIdOf(StompHeaderAccessor.wrap(event.getMessage()))
                .ifPresent(deviceId -> updatePresence(deviceId, true));
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        deviceIdOf(accessor).ifPresent(deviceId -> updatePresence(deviceId, false));
    }

    private void updatePresence(String deviceId, boolean online) {
        Device device = deviceService.setOnline(deviceId, online);
        log.info("Device '{}' is now {}", deviceId, online ? "ONLINE" : "OFFLINE");
        broadcastService.broadcastDeviceStatus(DeviceResponse.from(device));
    }

    private java.util.Optional<String> deviceIdOf(StompHeaderAccessor accessor) {
        if (accessor == null) {
            return java.util.Optional.empty();
        }
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return java.util.Optional.empty();
        }
        Object deviceId = sessionAttributes.get(DeviceIdHandshakeInterceptor.DEVICE_ID_ATTRIBUTE);
        return deviceId == null ? java.util.Optional.empty() : java.util.Optional.of(deviceId.toString());
    }
}
