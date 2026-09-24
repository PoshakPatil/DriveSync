package com.drivesync.watcher.watch;

import com.drivesync.watcher.model.FileChangeEvent;

/**
 * Callback for confirmed file changes. Kept as a tiny functional interface so
 * FileWatcherService doesn't need to know or care what happens to a change
 * once detected - milestone 2 wires this to a console logger, later
 * milestones wire it to the sync client that talks to the coordinator.
 */
@FunctionalInterface
public interface FileChangeListener {
    void onChange(FileChangeEvent event);
}
