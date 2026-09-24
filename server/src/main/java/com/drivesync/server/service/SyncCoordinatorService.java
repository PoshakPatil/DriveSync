package com.drivesync.server.service;

import com.drivesync.server.dto.ChangeResponse;
import com.drivesync.server.dto.ChangeSubmissionRequest;
import com.drivesync.server.model.FileChangeRecord;
import com.drivesync.server.model.FileState;
import com.drivesync.server.repository.FileChangeRecordRepository;
import com.drivesync.server.repository.FileStateRepository;
import com.drivesync.server.ws.ChangeBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The core coordination logic: accepting change submissions from devices and
 * serving catch-up requests from reconnecting devices.
 *
 * Duplicate detection (server-side, independent of the watcher's own
 * client-side dedup) and conflict detection (delegated to ConflictResolver)
 * both live here, checked in that order, before a submission is accepted as
 * a normal last-write update.
 */
@Service
public class SyncCoordinatorService {

    private static final Logger log = LoggerFactory.getLogger(SyncCoordinatorService.class);

    private final FileChangeRecordRepository changeRepository;
    private final FileStateRepository fileStateRepository;
    private final DeviceService deviceService;
    private final ChangeBroadcastService broadcastService;
    private final ConflictResolver conflictResolver;

    public SyncCoordinatorService(FileChangeRecordRepository changeRepository,
                                   FileStateRepository fileStateRepository,
                                   DeviceService deviceService,
                                   ChangeBroadcastService broadcastService,
                                   ConflictResolver conflictResolver) {
        this.changeRepository = changeRepository;
        this.fileStateRepository = fileStateRepository;
        this.deviceService = deviceService;
        this.broadcastService = broadcastService;
        this.conflictResolver = conflictResolver;
    }

    @Transactional
    public ChangeResponse submitChange(ChangeSubmissionRequest request) {
        // A device submitting changes counts as activity, even without a live WS session -
        // keeps "last seen" honest for devices that are only ever polled/REST-driven.
        deviceService.registerOrTouch(request.deviceId(), null);

        Optional<FileState> existingState = fileStateRepository.findById(request.relativePath());

        boolean isDuplicate = existingState.isPresent()
                && Objects.equals(existingState.get().getCurrentHash(), request.contentHash())
                && existingState.get().getLastChangeType() == request.changeType();

        if (isDuplicate) {
            log.debug("Ignoring duplicate submission for '{}' from device '{}' (hash unchanged)",
                    request.relativePath(), request.deviceId());
            FileChangeRecord last = changeRepository.findById(existingState.get().getLastChangeId())
                    .orElseThrow();
            return ChangeResponse.from(last, true);
        }

        if (conflictResolver.isConflict(existingState.orElse(null), request)) {
            return conflictResolver.handleConflict(existingState.get(), request);
        }

        FileChangeRecord record = new FileChangeRecord(
                request.deviceId(),
                request.relativePath(),
                request.changeType(),
                request.contentHash(),
                request.sizeBytes(),
                request.clientDetectedAt(),
                Instant.now());
        record = changeRepository.save(record);

        FileState state = existingState.orElseGet(() -> new FileState(request.relativePath()));
        state.apply(record);
        fileStateRepository.save(state);

        log.info("Recorded {} on '{}' from device '{}' (change id {})",
                request.changeType(), request.relativePath(), request.deviceId(), record.getId());

        ChangeResponse response = ChangeResponse.from(record, false);

        // Broadcasting is deferred to run AFTER this transaction commits, not inline
        // here. This method is still mid-transaction at this point (the @Transactional
        // proxy commits only once the method returns) - broadcasting now would risk
        // telling other devices about a change that a later error in this same
        // transaction then rolls back. Registering an afterCommit hook guarantees the
        // row is durably saved before anyone is notified about it. Duplicates never
        // reach this line at all, so there's nothing to broadcast for a no-op.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                broadcastService.broadcastChange(response);
            }
        });

        return response;
    }

    /**
     * Catch-up sync: everything that happened after `since`, excluding the
     * requesting device's own submissions (it already has those - it made them).
     */
    @Transactional(readOnly = true)
    public List<FileChangeRecord> fetchChangesSince(long since, String excludeDeviceId) {
        if (excludeDeviceId != null && !excludeDeviceId.isBlank()) {
            return changeRepository.findByIdGreaterThanAndDeviceIdNotOrderByIdAsc(since, excludeDeviceId);
        }
        return changeRepository.findByIdGreaterThanOrderByIdAsc(since);
    }

    /** Most recent changes, newest first - what the dashboard's activity feed shows on load. */
    @Transactional(readOnly = true)
    public List<FileChangeRecord> recentActivity(int limit) {
        return changeRepository.findAllByOrderByIdDesc(PageRequest.of(0, limit));
    }
}
