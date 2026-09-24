package com.drivesync.server.controller;

import com.drivesync.server.dto.ConflictResolutionRequest;
import com.drivesync.server.dto.ConflictResponse;
import com.drivesync.server.model.Conflict;
import com.drivesync.server.repository.ConflictRepository;
import com.drivesync.server.service.ConflictResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Surfaces detected conflicts (dashboard, milestone 6) and lets a user
 * manually override the automatic "newer edit wins the name" resolution -
 * see ConflictResolver for the actual detection/resolution logic.
 */
@RestController
@RequestMapping("/api/conflicts")
public class ConflictController {

    private final ConflictRepository conflictRepository;
    private final ConflictResolver conflictResolver;

    public ConflictController(ConflictRepository conflictRepository, ConflictResolver conflictResolver) {
        this.conflictRepository = conflictRepository;
        this.conflictResolver = conflictResolver;
    }

    @GetMapping
    public List<ConflictResponse> list(@RequestParam(required = false) Boolean resolved) {
        List<Conflict> conflicts = resolved == null
                ? conflictRepository.findAllByOrderByDetectedAtDesc()
                : conflictRepository.findByResolvedOrderByDetectedAtDesc(resolved);
        return conflicts.stream().map(ConflictResponse::from).toList();
    }

    @PostMapping("/{id}/resolve")
    public ConflictResponse resolve(@PathVariable Long id, @Valid @RequestBody ConflictResolutionRequest request) {
        Conflict conflict = conflictRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No conflict with id " + id));
        try {
            return conflictResolver.resolveManually(conflict, request.keepDeviceId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
