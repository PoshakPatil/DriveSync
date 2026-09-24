package com.drivesync.server.repository;

import com.drivesync.server.model.FileChangeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileChangeRecordRepository extends JpaRepository<FileChangeRecord, Long> {

    /** Catch-up fetch for a reconnecting device: everything since the last id it saw. */
    List<FileChangeRecord> findByIdGreaterThanOrderByIdAsc(Long since);

    /** Same, but skips the requesting device's own changes (it doesn't need to catch up on itself). */
    List<FileChangeRecord> findByIdGreaterThanAndDeviceIdNotOrderByIdAsc(Long since, String excludeDeviceId);

    /** Most recent N records across all devices/paths, for the dashboard activity feed. */
    List<FileChangeRecord> findAllByOrderByIdDesc(org.springframework.data.domain.Pageable pageable);
}
