package com.drivesync.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the DriveSync coordinator server.
 *
 * This is the "hub" in the client-server sync model: watcher clients on each
 * device never talk to each other directly, they only talk to this server,
 * which stores metadata (file hashes, versions, device state) and relays
 * change notifications between devices.
 */
@SpringBootApplication
public class DriveSyncServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriveSyncServerApplication.class, args);
    }
}
