package com.drivesync.watcher.sync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, per relative path, the last server-assigned change id this device
 * knows about - whether because it made that change itself and got the id
 * back, or because it applied someone else's change (catch-up or live push)
 * carrying that id.
 *
 * This is what lets every outgoing submission say "I'm building on version
 * N of this file" (see ChangeSubmissionRequest.baseChangeId server-side),
 * which is the whole mechanism conflict detection is built on - see
 * ConflictResolver's doc comment on the server for the full picture.
 *
 * In-memory only, like FileWatcherService's own knownHashes map - lost on
 * restart. A real production client would persist this (and knownHashes)
 * locally so a restart doesn't lose track of "what did I last know", but
 * that's out of scope for v1 (see LEARNING.md for what this simplification
 * means for conflict detection across a restart).
 */
public class PathVersionTracker {

    private final Map<String, Long> lastKnownChangeId = new ConcurrentHashMap<>();

    public Long get(String relativePath) {
        return lastKnownChangeId.get(relativePath);
    }

    public void record(String relativePath, Long changeId) {
        if (changeId != null) {
            lastKnownChangeId.put(relativePath, changeId);
        }
    }
}
