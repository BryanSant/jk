// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

    private Hashing() {}

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 of a file's contents, computed by streaming through a fixed buffer so a
     * multi-hundred-MB artifact is never held in memory at once.
     */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest md = newSha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** A fresh SHA-256 {@link MessageDigest} for incremental hashing. */
    public static MessageDigest newSha256() {
        return newDigest("SHA-256");
    }

    public static byte[] sha256(byte[] data) {
        return newSha256().digest(data);
    }

    /**
     * A fresh {@link MessageDigest} for {@code algorithm} (e.g. {@code "SHA-256"}, {@code "SHA-1"},
     * {@code "MD5"}) — the multi-algorithm case (Maven checksums, streaming multi-digest). The named
     * algorithms are mandated on every JVM, so absence is a JVM defect, not a recoverable error.
     */
    public static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " not available on this JVM", e);
        }
    }

    /** Lowercase hex of raw digest bytes — the one spelling of {@code HexFormat.of().formatHex(..)}. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Hex digest of {@code data} under {@code algorithm} (one-shot). */
    public static String hashHex(String algorithm, byte[] data) {
        return hex(newDigest(algorithm).digest(data));
    }
}
