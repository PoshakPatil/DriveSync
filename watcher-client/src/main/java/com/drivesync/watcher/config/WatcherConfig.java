package com.drivesync.watcher.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Watcher configuration, resolved in priority order (highest wins):
 *   1. command-line arguments (--folder, --device-id, --server, --debounce-ms)
 *   2. environment variables (WATCH_FOLDER, DEVICE_ID, SERVER_ADDRESS, DEBOUNCE_MS)
 *   3. built-in defaults
 *
 * Nothing is hardcoded: the same jar runs as "device A" or "device B" (or
 * points at a different coordinator entirely) purely based on how it's
 * launched, which is what makes running two watcher instances for local
 * testing possible without maintaining two copies of the code.
 */
public class WatcherConfig {

    private final Path watchFolder;
    private final String deviceId;
    private final String serverHttpBaseUrl;
    private final String serverWsUrl;
    private final long debounceMillis;

    private WatcherConfig(Path watchFolder, String deviceId, String serverHttpBaseUrl,
                           String serverWsUrl, long debounceMillis) {
        this.watchFolder = watchFolder;
        this.deviceId = deviceId;
        this.serverHttpBaseUrl = serverHttpBaseUrl;
        this.serverWsUrl = serverWsUrl;
        this.debounceMillis = debounceMillis;
    }

    public static WatcherConfig fromArgs(String[] args) {
        Map<String, String> parsed = parseArgs(args);

        String folder = firstNonNull(
                parsed.get("folder"),
                System.getenv("WATCH_FOLDER"));
        if (folder == null) {
            throw new IllegalArgumentException(
                    "Watch folder is required. Pass --folder <path> or set WATCH_FOLDER.");
        }

        String deviceId = firstNonNull(
                parsed.get("device-id"),
                System.getenv("DEVICE_ID"),
                defaultDeviceId());

        String server = firstNonNull(
                parsed.get("server"),
                System.getenv("SERVER_ADDRESS"),
                "http://localhost:8080");
        // strip a trailing slash so callers can safely do serverHttpBaseUrl + "/api/..."
        String httpBase = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        String wsUrl = httpBase.replaceFirst("^http", "ws") + "/ws";

        long debounce = Long.parseLong(firstNonNull(
                parsed.get("debounce-ms"),
                System.getenv("DEBOUNCE_MS"),
                "800"));

        return new WatcherConfig(Paths.get(folder).toAbsolutePath().normalize(),
                deviceId, httpBase, wsUrl, debounce);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--") && i + 1 < args.length) {
                map.put(arg.substring(2), args[i + 1]);
                i++;
            }
        }
        return map;
    }

    private static String defaultDeviceId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "device-" + System.currentTimeMillis();
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    public Path watchFolder() {
        return watchFolder;
    }

    public String deviceId() {
        return deviceId;
    }

    public String serverHttpBaseUrl() {
        return serverHttpBaseUrl;
    }

    public String serverWsUrl() {
        return serverWsUrl;
    }

    public long debounceMillis() {
        return debounceMillis;
    }

    @Override
    public String toString() {
        return "WatcherConfig{folder=" + watchFolder + ", deviceId=" + deviceId
                + ", server=" + serverHttpBaseUrl + ", debounceMs=" + debounceMillis + "}";
    }
}
