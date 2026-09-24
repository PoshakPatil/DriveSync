package com.drivesync.server.config;

import com.drivesync.server.ws.DeviceIdHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Wires up STOMP-over-WebSocket messaging for real-time push.
 *
 * The protocol split in this project is deliberate:
 *   - REST (SyncCoordinatorController) is used for anything a client
 *     actively asks for or submits: registering, submitting a change,
 *     catch-up fetch, downloading blob content.
 *   - WebSocket/STOMP is used ONLY for the server telling already-connected
 *     clients "something happened" in real time - a thin, one-way
 *     notification channel. Clients never SEND data over STOMP in this
 *     project (hence there's no @MessageMapping handler here) - they only
 *     subscribe to topics and receive.
 *
 * This keeps the "what actually happened" logic (validation, dedup,
 * persistence) entirely in one place (SyncCoordinatorService, reached via
 * REST), with WebSocket as a pure broadcast side-channel layered on top -
 * simpler to reason about than accepting writes through two different
 * transports.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // A simple in-memory broker is enough for two local devices; a real
        // multi-instance deployment would swap this for an external broker
        // (e.g. RabbitMQ) via enableStompBrokerRelay(), but that's out of
        // scope for a single coordinator process.
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // Local dev only: both the Java watcher client and the Vite dev
                // server (different origin/port) need to connect. A real
                // deployment would restrict this to the actual frontend origin.
                .setAllowedOriginPatterns("*")
                .addInterceptors(new DeviceIdHandshakeInterceptor());
    }
}
