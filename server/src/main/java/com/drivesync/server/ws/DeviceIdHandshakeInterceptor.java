package com.drivesync.server.ws;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Pulls "?deviceId=..." off the WebSocket handshake URL and stashes it in the
 * WebSocket session's attribute map, so later STOMP lifecycle events
 * (connect/disconnect - see PresenceEventListener) can identify WHICH device
 * connected or disconnected. STOMP's CONNECT frame headers would be another
 * place to carry this, but a query param on the handshake URL is simpler for
 * both this Java client and a browser-based STOMP client to set.
 */
public class DeviceIdHandshakeInterceptor implements HandshakeInterceptor {

    public static final String DEVICE_ID_ATTRIBUTE = "deviceId";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String deviceId = servletRequest.getServletRequest().getParameter("deviceId");
            if (deviceId != null && !deviceId.isBlank()) {
                attributes.put(DEVICE_ID_ATTRIBUTE, deviceId);
            }
        }
        return true; // never reject the handshake for a missing deviceId - just won't get presence tracking
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }
}
