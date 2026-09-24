package com.drivesync.server.repository;

import com.drivesync.server.model.FileState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileStateRepository extends JpaRepository<FileState, String> {
}
