package com.drivesync.server.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Simple liveness endpoint used to confirm frontend <-> backend connectivity
 * during scaffolding (milestone 1), and as a basic uptime check afterwards.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "drivesync-server",
                "timestamp", Instant.now().toString()
        );
    }
}
