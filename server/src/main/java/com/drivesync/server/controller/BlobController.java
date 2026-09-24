package com.drivesync.server.controller;

import com.drivesync.server.service.BlobStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

/**
 * Content transfer for the sync protocol: uploading and downloading the
 * actual bytes of a file, addressed by its SHA-256 hash. See
 * BlobStorageService for why this exists separately from Postgres.
 *
 * A watcher uploads a blob right after submitting the corresponding change
 * via SyncCoordinatorController; a device applying a change it learned about
 * (via WebSocket push or catch-up fetch) downloads the blob for that
 * change's hash and writes it to disk locally.
 */
@RestController
@RequestMapping("/api/sync/blobs")
public class BlobController {

    private final BlobStorageService blobStorageService;

    public BlobController(BlobStorageService blobStorageService) {
        this.blobStorageService = blobStorageService;
    }

    /** Cheap existence check so a client can skip re-uploading content the server already has. */
    @RequestMapping(value = "/{hash}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> exists(@PathVariable String hash) {
        try {
            return blobStorageService.exists(hash)
                    ? ResponseEntity.ok().build()
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping(value = "/{hash}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> upload(@PathVariable String hash, @RequestBody byte[] content) {
        try {
            boolean created = blobStorageService.storeIfAbsent(hash, content);
            return created ? ResponseEntity.status(HttpStatus.CREATED).build() : ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store blob", e);
        }
    }

    @GetMapping(value = "/{hash}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable String hash) {
        try {
            if (!blobStorageService.exists(hash)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(blobStorageService.retrieve(hash));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read blob", e);
        }
    }
}
