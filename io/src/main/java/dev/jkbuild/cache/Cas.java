// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import dev.jkbuild.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * SHA-256-keyed content-addressed store. Layout: {@code &lt;root&gt;/sha256/AB/CD/&lt;rest&gt;}
 * per PRD §4. Writes are atomic (write-then-rename); reads verify the hash.
 */
public final class Cas {

    private final Path root;

    public Cas(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public Path root() {
        return root;
    }

    public boolean contains(String sha256Hex) {
        return Files.exists(pathFor(sha256Hex));
    }

    /** Stable path for a hash. May or may not exist on disk. */
    public Path pathFor(String sha256Hex) {
        if (sha256Hex.length() < 4) {
            throw new IllegalArgumentException("sha256 hex must be at least 4 chars, got: " + sha256Hex);
        }
        return root.resolve("sha256")
                .resolve(sha256Hex.substring(0, 2))
                .resolve(sha256Hex.substring(2, 4))
                .resolve(sha256Hex.substring(4));
    }

    /**
     * Inverse of {@link #pathFor}: extract the hex hash from a path that
     * looks like a CAS object location, or {@link java.util.Optional#empty}
     * if it doesn't fit the layout. Used by the sweep when scanning tool
     * env JSONs and action records — any absolute path under
     * {@code <root>/sha256/AA/BB/<rest>} contributes its hash to the
     * reachable set, regardless of the file format it came from.
     */
    public java.util.Optional<String> hashFromPath(Path candidate) {
        Path normalised = candidate.toAbsolutePath().normalize();
        Path shaRoot = root.resolve("sha256");
        if (!normalised.startsWith(shaRoot)) return java.util.Optional.empty();
        Path rel = shaRoot.relativize(normalised);
        if (rel.getNameCount() != 3) return java.util.Optional.empty();
        String aa = rel.getName(0).toString();
        String bb = rel.getName(1).toString();
        String rest = rel.getName(2).toString();
        if (aa.length() != 2 || bb.length() != 2) return java.util.Optional.empty();
        String hex = aa + bb + rest;
        if (!isHex(hex)) return java.util.Optional.empty();
        return java.util.Optional.of(hex);
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /**
     * Write data into the CAS. Returns the on-disk path. Idempotent — if the
     * blob is already present and matches, the existing path is returned
     * without re-writing.
     */
    public Path put(byte[] data) throws IOException {
        String hex = Hashing.sha256Hex(data);
        Path target = pathFor(hex);
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".put-", ".tmp");
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return target;
    }

    /**
     * Materialise a CAS entry as a hard link to {@code source} when the
     * filesystem supports it; falls back to a byte copy otherwise.
     *
     * <p>This is the right primitive when an action has just produced a
     * file in its own output tree (e.g. {@code target/classes/Hello.class}
     * fresh out of javac) and we want the CAS to also reference it: one
     * inode, two paths. No double-write, no double-storage. The caller
     * supplies the hex hash so the file isn't re-read just to verify the
     * key — the caller already had to hash it to build the action record.
     *
     * <p>Idempotent. If a CAS entry for {@code hex} already exists the
     * source is left untouched.
     */
    public Path putByLink(Path source, String hex) throws IOException {
        Path target = pathFor(hex);
        if (Files.exists(target)) {
            return target;
        }
        Files.createDirectories(target.getParent());
        // Linking.linkOrCopy handles cross-filesystem fallback and parent
        // creation. Race with a concurrent putByLink: linkOrCopy deletes
        // any existing entry first, so the second writer overwrites the
        // first — same content, indistinguishable outcome.
        Linking.linkOrCopy(source, target);
        return target;
    }

    /**
     * Read bytes for a hash. Verifies the content actually hashes to the
     * expected value; throws if the on-disk blob is corrupted.
     */
    public byte[] read(String sha256Hex) throws IOException {
        Path file = pathFor(sha256Hex);
        if (!Files.exists(file)) {
            throw new IOException("blob not present in CAS: " + sha256Hex);
        }
        byte[] data = Files.readAllBytes(file);
        String actual = Hashing.sha256Hex(data);
        if (!actual.equals(sha256Hex)) {
            throw new IOException("CAS corruption: expected " + sha256Hex + " but got " + actual);
        }
        return data;
    }
}
