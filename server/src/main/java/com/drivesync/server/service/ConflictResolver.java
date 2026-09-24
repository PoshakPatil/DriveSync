package com.drivesync.server.service;

import com.drivesync.server.dto.ChangeResponse;
import com.drivesync.server.dto.ChangeSubmissionRequest;
import com.drivesync.server.dto.ConflictResponse;
import com.drivesync.server.model.ChangeType;
import com.drivesync.server.model.Conflict;
import com.drivesync.server.model.FileChangeRecord;
import com.drivesync.server.model.FileState;
import com.drivesync.server.repository.ConflictRepository;
import com.drivesync.server.repository.FileChangeRecordRepository;
import com.drivesync.server.repository.FileStateRepository;
import com.drivesync.server.ws.ChangeBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Detects and resolves conflicts: two devices independently changing the
 * same file path, neither having seen the other's change first. This is the
 * "offline edit" scenario the whole project exists for - see README's
 * origin story.
 *
 * ---------------------------------------------------------------------------
 * HOW DETECTION WORKS: optimistic concurrency via a "base version"
 * ---------------------------------------------------------------------------
 * Every change submission carries a `baseChangeId`: the change id the
 * submitting device believes is CURRENTLY authoritative for that path (the
 * last one it either made itself or applied from elsewhere). This is exactly
 * the same idea as a database's optimistic-locking "version" column, or how
 * Git detects a non-fast-forward push: if what you're building on top of
 * isn't what's actually there anymore, something happened without your
 * knowledge.
 *
 * A submission is a CONFLICT only when BOTH of these are true:
 *   1. Its baseChangeId does NOT match FileState.lastChangeId for that path
 *      (the device's knowledge is stale - something changed after the point
 *      it last knew about).
 *   2. The submitted content hash is genuinely DIFFERENT from what's
 *      currently on the server (if it happens to match anyway, the two
 *      devices coincidentally arrived at identical content - not a real
 *      conflict, nothing to reconcile).
 *
 * ---------------------------------------------------------------------------
 * HOW RESOLUTION WORKS: keep both, newer wins the original name
 * ---------------------------------------------------------------------------
 * Neither version is ever discarded. Whichever edit has the LATER
 * clientDetectedAt (the device's own clock, at the moment it detected the
 * change locally) keeps the original path; the other is preserved as
 * "name (conflicted copy - deviceId).ext". This choice is fully automatic
 * and immediate - the dashboard (milestone 6) surfaces it afterwards and
 * lets a user manually override which side keeps the original name via
 * resolveManually(), but the system never waits for that: "no silent
 * overwrites" is satisfied the instant the conflict is detected, because
 * both versions already exist as real, synced files before anyone looks
 * at a dashboard.
 */
@Service
public class ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolver.class);

    private final FileChangeRecordRepository changeRepository;
    private final FileStateRepository fileStateRepository;
    private final ConflictRepository conflictRepository;
    private final ChangeBroadcastService broadcastService;

    public ConflictResolver(FileChangeRecordRepository changeRepository,
                             FileStateRepository fileStateRepository,
                             ConflictRepository conflictRepository,
                             ChangeBroadcastService broadcastService) {
        this.changeRepository = changeRepository;
        this.fileStateRepository = fileStateRepository;
        this.conflictRepository = conflictRepository;
        this.broadcastService = broadcastService;
    }

    /** True if this submission's base knowledge is stale AND the content genuinely differs - a real conflict. */
    public boolean isConflict(FileState existingState, ChangeSubmissionRequest request) {
        if (existingState == null) {
            return false; // brand new path - nothing to conflict with
        }
        long submittedBase = request.baseChangeId() == null ? 0L : request.baseChangeId();
        if (submittedBase == existingState.getLastChangeId()) {
            return false; // caller's knowledge is current - a normal, non-conflicting update
        }
        return !Objects.equals(existingState.getCurrentHash(), request.contentHash());
    }

    /**
     * Handles an already-detected conflict end to end: figures out the winner
     * (later clientDetectedAt keeps the original path), persists the loser's
     * content under a new "conflicted copy" path exactly like a normal file,
     * records the Conflict for the dashboard, and returns the ChangeResponse
     * the submitting device should receive.
     */
    @Transactional
    public ChangeResponse handleConflict(FileState existingState, ChangeSubmissionRequest request) {
        FileChangeRecord existingRecord = changeRepository.findById(existingState.getLastChangeId())
                .orElseThrow(() -> new IllegalStateException(
                        "FileState for '%s' points at missing change id %d"
                                .formatted(request.relativePath(), existingState.getLastChangeId())));

        boolean incomingWins = request.clientDetectedAt().isAfter(existingRecord.getClientDetectedAt());

        String losingDeviceId = incomingWins ? existingRecord.getDeviceId() : request.deviceId();
        String losingHash = incomingWins ? existingRecord.getContentHash() : request.contentHash();
        long losingSize = incomingWins ? existingRecord.getSizeBytes() : request.sizeBytes();
        Instant losingTime = incomingWins ? existingRecord.getClientDetectedAt() : request.clientDetectedAt();

        String conflictedCopyPath = buildConflictedCopyPath(request.relativePath(), losingDeviceId);

        // The losing content is preserved as an ordinary new file, at a new path - it syncs
        // to every device through exactly the same mechanism as any other change (catch-up
        // fetch, live WebSocket push). No special-case sync logic needed for it downstream.
        FileChangeRecord conflictedCopyRecord = new FileChangeRecord(losingDeviceId, conflictedCopyPath,
                ChangeType.CREATED, losingHash, losingSize, losingTime, Instant.now());
        conflictedCopyRecord = changeRepository.save(conflictedCopyRecord);
        FileState conflictedCopyState = new FileState(conflictedCopyPath);
        conflictedCopyState.apply(conflictedCopyRecord);
        fileStateRepository.save(conflictedCopyState);

        FileChangeRecord winningRecord;
        if (incomingWins) {
            // The incoming submission is newer - accept it at the original path exactly like
            // a normal (non-conflicting) change.
            winningRecord = new FileChangeRecord(request.deviceId(), request.relativePath(),
                    request.changeType(), request.contentHash(), request.sizeBytes(),
                    request.clientDetectedAt(), Instant.now());
            winningRecord = changeRepository.save(winningRecord);
            existingState.apply(winningRecord);
            fileStateRepository.save(existingState);
        } else {
            // The pre-existing server state already reflects the winner - nothing changes
            // at the original path; the incoming (losing) submission never gets applied there.
            winningRecord = existingRecord;
        }

        Conflict conflict = new Conflict(request.relativePath(), conflictedCopyPath,
                winningRecord.getDeviceId(), winningRecord.getContentHash(), winningRecord.getSizeBytes(), winningRecord.getId(),
                losingDeviceId, losingHash, losingSize, conflictedCopyRecord.getId(),
                Instant.now());
        conflict = conflictRepository.save(conflict);

        log.warn("CONFLICT on '{}': '{}' (device {}) keeps the name, '{}' (device {}) saved as '{}'",
                request.relativePath(), winningRecord.getContentHash(), winningRecord.getDeviceId(),
                losingHash, losingDeviceId, conflictedCopyPath);

        FileChangeRecord finalWinningRecord = winningRecord;
        Conflict finalConflict = conflict;
        FileChangeRecord finalConflictedCopyRecord = conflictedCopyRecord;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // Order matters: the conflicted copy must exist before the corrective
                // write to the original path arrives, so a losing device never has a
                // moment where its own edit exists nowhere on disk.
                broadcastService.broadcastChange(ChangeResponse.from(finalConflictedCopyRecord, false));
                if (incomingWins) {
                    broadcastService.broadcastChange(ChangeResponse.from(finalWinningRecord, false));
                }
                broadcastService.broadcastConflict(ConflictResponse.from(finalConflict));
            }
        });

        boolean requesterLost = !incomingWins;
        return new ChangeResponse(
                winningRecord.getId(),
                request.deviceId(),
                request.relativePath(),
                request.changeType(),
                request.contentHash(),
                request.sizeBytes(),
                request.clientDetectedAt(),
                Instant.now(),
                false,
                true,
                requesterLost ? conflictedCopyPath : null,
                requesterLost ? winningRecord.getContentHash() : null,
                conflictedCopyRecord.getId()
        );
    }

    /** Manual override (dashboard): pick which device's version keeps the original filename. */
    @Transactional
    public ConflictResponse resolveManually(Conflict conflict, String keepDeviceId) {
        String chosenHash;
        long chosenSize;
        String chosenAsDeviceId;

        if (keepDeviceId.equals(conflict.getWinningDeviceId())) {
            chosenHash = conflict.getWinningHash();
            chosenSize = conflict.getWinningSizeBytes();
            chosenAsDeviceId = conflict.getWinningDeviceId();
        } else if (keepDeviceId.equals(conflict.getLosingDeviceId())) {
            chosenHash = conflict.getLosingHash();
            chosenSize = conflict.getLosingSizeBytes();
            chosenAsDeviceId = conflict.getLosingDeviceId();
        } else {
            throw new IllegalArgumentException(
                    "keepDeviceId must be one of the two devices involved in this conflict: "
                            + conflict.getWinningDeviceId() + " or " + conflict.getLosingDeviceId());
        }

        FileState currentState = fileStateRepository.findById(conflict.getRelativePath()).orElseThrow();
        if (!chosenHash.equals(currentState.getCurrentHash())) {
            FileChangeRecord record = new FileChangeRecord(chosenAsDeviceId, conflict.getRelativePath(),
                    ChangeType.MODIFIED, chosenHash, chosenSize, Instant.now(), Instant.now());
            record = changeRepository.save(record);
            currentState.apply(record);
            fileStateRepository.save(currentState);

            FileChangeRecord finalRecord = record;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcastService.broadcastChange(ChangeResponse.from(finalRecord, false));
                }
            });
        }

        conflict.markResolved(keepDeviceId, Instant.now());
        conflict = conflictRepository.save(conflict);

        ConflictResponse response = ConflictResponse.from(conflict);
        Conflict finalConflict = conflict;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcastService.broadcastConflict(ConflictResponse.from(finalConflict));
            }
        });
        return response;
    }

    /**
     * "notes.txt" + "laptop-a" -> "notes (conflicted copy - laptop-a).txt"
     * "docs/plan" + "laptop-b" -> "docs/plan (conflicted copy - laptop-b)"  (no extension to preserve)
     */
    static String buildConflictedCopyPath(String relativePath, String deviceId) {
        int lastSlash = relativePath.lastIndexOf('/');
        String dir = lastSlash == -1 ? "" : relativePath.substring(0, lastSlash + 1);
        String fileName = lastSlash == -1 ? relativePath : relativePath.substring(lastSlash + 1);

        int lastDot = fileName.lastIndexOf('.');
        String base = lastDot <= 0 ? fileName : fileName.substring(0, lastDot);
        String ext = lastDot <= 0 ? "" : fileName.substring(lastDot);

        return dir + base + " (conflicted copy - " + deviceId + ")" + ext;
    }
}
