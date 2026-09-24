package com.drivesync.server.controller;

import com.drivesync.server.dto.ChangeResponse;
import com.drivesync.server.dto.ChangeSubmissionRequest;
import com.drivesync.server.service.SyncCoordinatorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST surface for the sync protocol's two non-realtime operations:
 *   - a device reporting a local change it detected
 *   - a device asking "what did I miss" (catch-up sync after being offline)
 *
 * The third operation - pushing a just-submitted change to OTHER connected
 * devices in real time - is deliberately not here; it's WebSocket-based and
 * lives in the ws package, added in milestone 4. This controller only ever
 * talks in terms of the durable log (FileChangeRecord), which is what makes
 * catch-up possible in the first place: nothing here depends on any device
 * being currently connected.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncCoordinatorController {

    private final SyncCoordinatorService syncCoordinatorService;

    public SyncCoordinatorController(SyncCoordinatorService syncCoordinatorService) {
        this.syncCoordinatorService = syncCoordinatorService;
    }

    @PostMapping("/changes")
    public ChangeResponse submitChange(@Valid @RequestBody ChangeSubmissionRequest request) {
        return syncCoordinatorService.submitChange(request);
    }

    /**
     * @param since         return only changes with id greater than this (0 = everything)
     * @param excludeDevice optional - typically the caller's own device id, so it
     *                      doesn't get handed back changes it made itself
     */
    @GetMapping("/changes")
    public List<ChangeResponse> fetchChangesSince(
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(required = false) String excludeDevice) {
        return syncCoordinatorService.fetchChangesSince(since, excludeDevice).stream()
                .map(record -> ChangeResponse.from(record, false))
                .toList();
    }

    /** Newest-first, for the dashboard's activity feed - distinct from the ascending catch-up order above. */
    @GetMapping("/activity")
    public List<ChangeResponse> recentActivity(@RequestParam(defaultValue = "50") int limit) {
        return syncCoordinatorService.recentActivity(limit).stream()
                .map(record -> ChangeResponse.from(record, false))
                .toList();
    }
}
