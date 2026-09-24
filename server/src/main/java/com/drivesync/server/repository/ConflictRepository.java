package com.drivesync.server.repository;

import com.drivesync.server.model.Conflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConflictRepository extends JpaRepository<Conflict, Long> {
    List<Conflict> findByResolvedOrderByDetectedAtDesc(boolean resolved);
    List<Conflict> findAllByOrderByDetectedAtDesc();
}
