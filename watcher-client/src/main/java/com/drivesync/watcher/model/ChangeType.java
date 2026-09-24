package com.drivesync.watcher.model;

/**
 * What kind of change happened to a file, from the watcher's point of view.
 *
 * Note there is no RENAMED type: the OS-level watch APIs (and Java's WatchService
 * on top of them) generally report a rename as a DELETE of the old name followed
 * by a CREATE of the new name, with no reliable link between the two events.
 * Detecting "this was actually a rename" would require heuristics (matching
 * hashes between a recent delete and a recent create) that are out of scope for v1.
 */
public enum ChangeType {
    CREATED,
    MODIFIED,
    DELETED
}
