package com.drivesync.server.model;

/**
 * Mirrors watcher-client's ChangeType enum. The two aren't shared via a common
 * library module on purpose - server and watcher are independent deployables
 * that only agree on a JSON wire format (see dto package), the same way any
 * client/server pair talking over HTTP would. A shared module would couple
 * their release cycles for no real benefit at this project's size.
 */
public enum ChangeType {
    CREATED,
    MODIFIED,
    DELETED
}
