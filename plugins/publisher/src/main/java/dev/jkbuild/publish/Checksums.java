// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.publish;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Hex digests of artifact bytes for the four checksum files every Maven
 * upload ships alongside the artifact (PRD §21.2): {@code .md5},
 * {@code .sha1}, {@code .sha256}, {@code .sha512}.
 *
 * <p>Maven Central rejects uploads without all four; mirrors generally
 * accept any subset but we emit all four for consistency.
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
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            return hex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            // All four are guaranteed by the JDK security provider list.
            throw new IllegalStateException(algorithm + " not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
