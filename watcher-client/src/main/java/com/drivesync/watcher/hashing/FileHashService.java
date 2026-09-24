package com.drivesync.watcher.hashing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes SHA-256 content hashes.
 *
 * WHY a content hash instead of relying on file size + last-modified time:
 * timestamps and sizes are cheap but unreliable signals. Saving a file with
 * identical content still updates its mtime (and some editors even rewrite
 * files with the same byte count but different bytes). A content hash is the
 * only way to answer the real question the sync system cares about: "did the
 * bytes actually change?" That's what lets us skip re-syncing a file that was
 * merely touched/resaved without modification, and it's also the basis for
 * conflict detection later (two devices independently producing different
 * hashes for the same path is the definition of a conflict).
 *
 * SHA-256 specifically: cryptographically strong enough that accidental hash
 * collisions between different file contents are not a practical concern,
 * built into the JDK (no extra dependency), and fast enough for the file
 * sizes this project targets (small personal files, not video libraries -
 * see README scope notes).
 */
public class FileHashService {

    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    /**
     * Streams the file through the digest in fixed-size chunks rather than
     * loading it into memory as one byte array, so this scales to larger
     * files without a memory spike proportional to file size.
     */
    public String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return toHex(digest.digest());
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available on every standard JDK implementation,
            // so this is unreachable in practice - but the checked exception still has
            // to go somewhere, and hiding it as unchecked is honest about that guarantee.
            throw new IllegalStateException(ALGORITHM + " is not available in this JVM", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
