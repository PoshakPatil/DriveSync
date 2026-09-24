package com.drivesync.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stores and retrieves raw file CONTENT (bytes), keyed by its SHA-256 hash -
 * deliberately NOT in Postgres, which per the project's scope only ever
 * holds metadata (paths, hashes, timestamps, device ids). This is a plain
 * directory on the coordinator server's own disk instead.
 *
 * WHY content-addressed storage (filename = hash of the content):
 *   1. Free deduplication - if two devices, or two different files, ever
 *      produce identical bytes, they're stored once. This falls straight out
 *      of naming files by hash rather than by path/device.
 *   2. Free integrity checking - see storeIfAbsent() below: the server
 *      recomputes the hash of whatever bytes it's given and REFUSES to
 *      trust a caller-supplied hash that doesn't match, the same way a
 *      package manager verifies a checksum instead of trusting a claimed one.
 *   3. It mirrors the watcher's own change-detection model (milestone 2),
 *      which is entirely built around "the hash of the content IS its
 *      identity" - keeping that idea consistent end to end is easier to
 *      reason about than switching models between client and server.
 */
@Service
public class BlobStorageService {

    private static final Logger log = LoggerFactory.getLogger(BlobStorageService.class);

    private final Path blobDir;

    public BlobStorageService(@Value("${drivesync.blob-storage-dir:./data/blobs}") String blobStorageDir) {
        this.blobDir = Paths.get(blobStorageDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(blobDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create blob storage directory: " + blobDir, e);
        }
        log.info("Blob storage directory: {}", blobDir);
    }

    public boolean exists(String hash) {
        return Files.exists(pathFor(hash));
    }

    /**
     * @return true if the content was newly stored, false if a blob with this
     *         hash already existed (content-addressed dedup - nothing to do)
     * @throws IllegalArgumentException if the actual SHA-256 of `content`
     *         doesn't match the claimed `hash` - the server never trusts a
     *         caller's claim about its own data's identity.
     */
    public boolean storeIfAbsent(String hash, byte[] content) throws IOException {
        String actualHash = sha256(content);
        if (!actualHash.equalsIgnoreCase(hash)) {
            throw new IllegalArgumentException(
                    "Claimed hash %s does not match actual content hash %s".formatted(hash, actualHash));
        }

        Path target = pathFor(hash);
        if (Files.exists(target)) {
            return false;
        }

        // Write to a temp file first, then atomically move into place, so a concurrent
        // reader can never observe a partially-written blob under its final name.
        Path tmp = Files.createTempFile(blobDir, "upload-", ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }

    public byte[] retrieve(String hash) throws IOException {
        return Files.readAllBytes(pathFor(hash));
    }

    private Path pathFor(String hash) {
        // Hashes are hex SHA-256 (fixed charset/length), so no path-traversal risk from
        // untrusted input reaching the filesystem here - still worth a defensive check.
        if (!hash.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Not a valid SHA-256 hex hash: " + hash);
        }
        return blobDir.resolve(hash.toLowerCase());
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        }
    }
}
