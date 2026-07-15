// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.publish;

import build.jumpkick.util.Hashing;

/**
 * Hex digests of artifact bytes for the four checksum files every Maven upload ships alongside the
 * artifact (PRD §21.2): {@code .md5}, {@code .sha1}, {@code .sha256}, {@code .sha512}.
 *
 * <p>Maven Central rejects uploads without all four; mirrors generally accept any subset but we
 * emit all four for consistency.
 */
public final class Checksums {

    private Checksums() {}

    public record Set(String md5, String sha1, String sha256, String sha512) {}

    public static Set of(byte[] data) {
        return new Set(
                digestHex(data, "MD5"),
                digestHex(data, "SHA-1"),
                digestHex(data, "SHA-256"),
                digestHex(data, "SHA-512"));
    }

    public static String md5Hex(byte[] data) {
        return digestHex(data, "MD5");
    }

    public static String sha1Hex(byte[] data) {
        return digestHex(data, "SHA-1");
    }

    public static String sha256Hex(byte[] data) {
        return digestHex(data, "SHA-256");
    }

    public static String sha512Hex(byte[] data) {
        return digestHex(data, "SHA-512");
    }

    private static String digestHex(byte[] data, String algorithm) {
        return Hashing.hashHex(algorithm, data);
    }
}
