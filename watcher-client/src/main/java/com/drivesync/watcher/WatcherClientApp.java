package com.drivesync.watcher;

import com.drivesync.watcher.config.WatcherConfig;
import com.drivesync.watcher.hashing.FileHashService;
import com.drivesync.watcher.sync.ChangeDto;
import com.drivesync.watcher.sync.LiveSyncSubscriber;
import com.drivesync.watcher.sync.LocalChangeUploader;
import com.drivesync.watcher.sync.PathVersionTracker;
import com.drivesync.watcher.sync.RemoteChangeApplier;
import com.drivesync.watcher.sync.SyncApiClient;
import com.drivesync.watcher.watch.FileWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for a single DriveSync watcher instance, and the class that
 * wires together everything built in milestones 2-4:
 *   - FileWatcherService (milestone 2): detects local changes via hashing
 *   - SyncApiClient + LocalChangeUploader (milestone 3/4): reports local
 *     changes to the coordinator and uploads their content
 *   - SyncApiClient catch-up fetch + RemoteChangeApplier (milestone 3/4):
 *     applies changes that happened while this device was offline
 *   - LiveSyncSubscriber + RemoteChangeApplier (milestone 4): applies
 *     changes pushed in real time while this device is connected
 *
 * Startup sequence matters here: register -> catch up on missed changes ->
 * THEN start watching locally and connect for live updates. Doing it in
 * this order means the local folder is brought up to date with whatever
 * happened while this device was gone BEFORE the watcher starts reacting to
 * local filesystem state, avoiding a window where a stale local file looks
 * like a "new local change" simply because catch-up hasn't run yet.
 */
public class WatcherClientApp {

    private static final Logger log = LoggerFactory.getLogger(WatcherClientApp.class);

    public static void main(String[] args) throws Exception {
        WatcherConfig config = WatcherConfig.fromArgs(args);
        log.info("Starting DriveSync watcher: {}", config);

        SyncApiClient apiClient = new SyncApiClient(config.serverHttpBaseUrl());

        try {
            apiClient.registerDevice(config.deviceId(), config.deviceId());
            log.info("Registered with coordinator as '{}'", config.deviceId());
        } catch (Exception e) {
            log.warn("Could not register with coordinator at startup ({}); continuing anyway - "
                    + "local change detection still works, sync will resume once it's reachable.", e.getMessage());
        }

        FileHashService hashService = new FileHashService();
        PathVersionTracker versionTracker = new PathVersionTracker();

        LocalChangeUploader uploader = new LocalChangeUploader(apiClient, config.watchFolder(), versionTracker);
        FileWatcherService watcherService = new FileWatcherService(
                config.watchFolder(), config.deviceId(), hashService, config.debounceMillis(), uploader);
        // Completes the circular wiring: LocalChangeUploader needs FileWatcherService (to
        // apply corrective writes on conflict) but FileWatcherService's constructor needs
        // LocalChangeUploader (as its FileChangeListener) - see attachWatcherService's doc.
        uploader.attachWatcherService(watcherService);

        RemoteChangeApplier remoteChangeApplier = new RemoteChangeApplier(apiClient, watcherService, versionTracker);

        // Catch-up BEFORE starting the watcher's own initial scan, so files that
        // changed remotely while this device was offline are already correct on
        // disk by the time local change detection begins.
        performCatchUp(apiClient, config.deviceId(), remoteChangeApplier);

        watcherService.start();

        LiveSyncSubscriber liveSyncSubscriber = new LiveSyncSubscriber(
                URI.create(config.serverWsUrl() + "?deviceId=" + config.deviceId()),
                remoteChangeApplier);
        try {
            liveSyncSubscriber.connect();
            log.info("Connected for live updates at {}", config.serverWsUrl());
        } catch (Exception e) {
            log.warn("Could not connect for live updates ({}); this device will only sync on next catch-up.",
                    e.getMessage());
        }

        CountDownLatch keepAlive = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down watcher for device '{}'", config.deviceId());
            liveSyncSubscriber.disconnect();
            watcherService.stop();
            keepAlive.countDown();
        }));

        keepAlive.await();
    }

    private static void performCatchUp(SyncApiClient apiClient, String deviceId, RemoteChangeApplier applier) {
        try {
            // since=0: this watcher doesn't persist a cursor across restarts (v1
            // simplification - see LEARNING.md), so every startup re-fetches full
            // history and relies on RemoteChangeApplier + the server's dedup to make
            // re-applying already-current files a cheap no-op rather than a bug.
            List<ChangeDto> missed = apiClient.fetchChangesSince(0, deviceId);
            log.info("Catch-up: {} change(s) to apply from before this session", missed.size());
            for (ChangeDto change : missed) {
                applier.apply(change);
            }
        } catch (Exception e) {
            log.warn("Catch-up fetch failed ({}); starting with only local state.", e.getMessage());
        }
    }
}
