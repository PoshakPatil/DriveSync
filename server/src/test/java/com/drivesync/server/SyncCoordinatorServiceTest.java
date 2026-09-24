package com.drivesync.server;

import com.drivesync.server.dto.ChangeResponse;
import com.drivesync.server.dto.ChangeSubmissionRequest;
import com.drivesync.server.model.ChangeType;
import com.drivesync.server.model.FileChangeRecord;
import com.drivesync.server.service.SyncCoordinatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs against the real local Postgres (this is a small learning project,
 * not a codebase with CI infra for spinning up Testcontainers) but every
 * test method is wrapped in a transaction that's rolled back afterwards, so
 * nothing written here persists or interferes with manually-inserted data.
 */
@SpringBootTest
@Transactional
class SyncCoordinatorServiceTest {

    @Autowired
    private SyncCoordinatorService syncCoordinatorService;

    private static ChangeSubmissionRequest submission(String device, String path, ChangeType type, String hash,
                                                        Instant detectedAt, Long baseChangeId) {
        return new ChangeSubmissionRequest(device, path, type, hash, 42, detectedAt, baseChangeId);
    }

    /** Convenience for tests that don't care about conflict detection - always claims an up-to-date base. */
    private static ChangeSubmissionRequest submission(String device, String path, ChangeType type, String hash) {
        return submission(device, path, type, hash, Instant.now(), null);
    }

    @Test
    void firstSubmissionForAPathIsRecordedAsNotDuplicate() {
        ChangeResponse response = syncCoordinatorService.submitChange(
                submission("device-1", "a.txt", ChangeType.CREATED, "hash-1"));

        assertFalse(response.duplicate());
        assertNotNull(response.id());
    }

    @Test
    void resubmittingTheSameHashAndTypeIsFlaggedAsDuplicateAndDoesNotCreateANewRecord() {
        ChangeResponse first = syncCoordinatorService.submitChange(
                submission("device-1", "b.txt", ChangeType.CREATED, "hash-1"));

        ChangeResponse second = syncCoordinatorService.submitChange(
                submission("device-1", "b.txt", ChangeType.CREATED, "hash-1"));

        assertTrue(second.duplicate());
        assertEquals(first.id(), second.id(), "duplicate submission should point back at the original record");
    }

    @Test
    void aGenuineContentChangeWithAnUpToDateBaseIsRecordedAsANewEntryNotAConflict() {
        ChangeResponse first = syncCoordinatorService.submitChange(
                submission("device-1", "c.txt", ChangeType.CREATED, "hash-1"));

        // Same device, building on the base it was just told about - a normal sequential edit.
        ChangeResponse changed = syncCoordinatorService.submitChange(
                submission("device-1", "c.txt", ChangeType.MODIFIED, "hash-2", Instant.now(), first.id()));

        assertFalse(changed.duplicate());
        assertFalse(changed.conflict());
        assertNotEquals(first.id(), changed.id());
    }

    @Test
    void catchUpFetchExcludesTheRequestingDevicesOwnChanges() {
        syncCoordinatorService.submitChange(submission("device-a", "d.txt", ChangeType.CREATED, "hash-a"));
        syncCoordinatorService.submitChange(submission("device-b", "e.txt", ChangeType.CREATED, "hash-b"));

        List<FileChangeRecord> forDeviceB = syncCoordinatorService.fetchChangesSince(0, "device-b");

        assertTrue(forDeviceB.stream().anyMatch(r -> r.getRelativePath().equals("d.txt")));
        assertTrue(forDeviceB.stream().noneMatch(r -> r.getRelativePath().equals("e.txt")),
                "device-b should not receive its own change back");
    }

    @Test
    void catchUpFetchSinceCursorOnlyReturnsLaterChanges() {
        ChangeResponse first = syncCoordinatorService.submitChange(
                submission("device-a", "f.txt", ChangeType.CREATED, "hash-1"));
        syncCoordinatorService.submitChange(
                submission("device-a", "g.txt", ChangeType.CREATED, "hash-2"));

        List<FileChangeRecord> afterFirst = syncCoordinatorService.fetchChangesSince(first.id(), null);

        assertTrue(afterFirst.stream().noneMatch(r -> r.getId().equals(first.id())));
        assertTrue(afterFirst.stream().anyMatch(r -> r.getRelativePath().equals("g.txt")));
    }

    @Test
    void twoIndependentEditsToTheSamePathAreDetectedAsAConflictAndBothPreserved() {
        // Both devices start from the same baseline.
        ChangeResponse baseline = syncCoordinatorService.submitChange(
                submission("laptop-a", "shared.txt", ChangeType.CREATED, "hash-original", Instant.now(), null));

        Instant earlier = Instant.now();
        Instant later = earlier.plusSeconds(10);

        // laptop-a edits, based on the baseline it knows about - accepted normally, becomes
        // the current server-side truth for this path.
        ChangeResponse fromA = syncCoordinatorService.submitChange(
                submission("laptop-a", "shared.txt", ChangeType.MODIFIED, "hash-from-a", later, baseline.id()));
        assertFalse(fromA.conflict());

        // laptop-b independently edited the SAME original baseline BEFORE laptop-a's edit ever
        // happened, but was offline and only submits now - its baseChangeId still points at
        // `baseline`, which the server has since moved past. Different content -> conflict.
        // Because its own edit happened chronologically EARLIER than laptop-a's, laptop-a's
        // edit should win and keep the original filename.
        ChangeResponse fromB = syncCoordinatorService.submitChange(
                submission("laptop-b", "shared.txt", ChangeType.MODIFIED, "hash-from-b", earlier, baseline.id()));

        assertTrue(fromB.conflict(), "an edit based on stale knowledge with different content must be a conflict");
        assertNotNull(fromB.conflictedCopyPath(), "the losing submitter must be told where its own content was preserved");
        assertTrue(fromB.conflictedCopyPath().contains("conflicted copy"));
        assertTrue(fromB.conflictedCopyPath().contains("laptop-b"), "conflicted copy is named after the LOSING device");
        assertEquals("hash-from-a", fromB.winningContentHash(),
                "laptop-b lost, so it's told to restore the winner's (laptop-a's) content at the original path");

        // The original path must still reflect the winner (laptop-a's edit), untouched by the
        // losing submission.
        List<FileChangeRecord> history = syncCoordinatorService.fetchChangesSince(0, null);
        assertTrue(history.stream().anyMatch(r ->
                r.getRelativePath().equals(fromB.conflictedCopyPath()) && r.getContentHash().equals("hash-from-b")),
                "laptop-b's content must exist, unmodified, under the conflicted-copy path - never discarded");
    }
}
