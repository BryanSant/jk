// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.format.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content-based key computation for per-file format stamps. A stamp key encodes both the file
 * content <em>and</em> every formatter config parameter that could affect the output, so any config
 * change automatically invalidates all existing stamps.
 *
 * <p>Key structure (SHA-256 of):
 *
 * <ol>
 *   <li>{@value #VERSION} — format version prefix; change to bust all stamps when the key schema
 *       evolves.
 *   <li>Config descriptor — newline-delimited summary of style, versions, flags, and the worker JAR
 *       SHA (which captures OpenRewrite + Spotless versions).
 *   <li>Raw file bytes — the exact content that was determined clean.
 * </ol>
 *
 * <p>All methods return {@code null} on any crypto/I/O failure; callers treat {@code null} as a
 * cache miss (fail-open — formatting always runs).
 */
final class FormatStamp {

    static final String VERSION = "format-stamp-v1";

    private FormatStamp() {}

    /**
     * Compute the stamp key for a file whose raw bytes are {@code fileBytes} and whose effective
     * formatter configuration is {@code configDescriptor}. Returns {@code null} on failure.
     */
    static String computeKey(byte[] fileBytes, String configDescriptor) {
        if (fileBytes == null || configDescriptor == null) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            feed(md, VERSION);
            feed(md, configDescriptor);
            md.update(fileBytes);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Build the stable config descriptor from all formatter parameters. Each field that could change
     * the formatted output of a file contributes a line. The worker-JAR SHA captures the OpenRewrite
     * + Spotless versions in one token.
     */
    static String configDescriptor(
            String javaStyle,
            String javaVersion,
            String kotlinStyle,
            String kotlinVersion,
            boolean optimizeImports,
            String workerJarSha,
            Path rewriteConfigFile) {
        String rewriteHash = "none";
        if (rewriteConfigFile != null) {
            try {
                byte[] cfg = Files.readAllBytes(rewriteConfigFile);
                rewriteHash = sha256Hex(cfg);
            } catch (IOException e) {
                rewriteHash = "unreadable:" + rewriteConfigFile.getFileName();
            }
        }
        return String.join(
                "\n",
                "java-style:" + javaStyle,
                "java-version:" + javaVersion,
                "kotlin-style:" + kotlinStyle,
                "kotlin-version:" + kotlinVersion,
                "optimize-imports:" + optimizeImports,
                "worker-sha:" + workerJarSha,
                "rewrite-config-hash:" + rewriteHash);
    }

    /**
     * Read the formatter worker JAR's own SHA-256 from the embedded resource written by the build.
     * This captures the exact OpenRewrite + Spotless versions bundled in the fat JAR — any dependency
     * upgrade changes this value and automatically busts all existing stamps.
     */
    static String workerJarSha() {
        try (InputStream is = FormatStamp.class.getResourceAsStream("/META-INF/jk-formatter-sha256.txt")) {
            if (is == null) return "unknown";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    // -------------------------------------------------------------------------

    private static void feed(MessageDigest md, String value) {
        md.update((value + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            return "error";
        }
    }
}
