// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Writes Maven-compatible artifact files to {@code ~/.m2/repository} (or the path returned by
 * {@link M2Dirs#localRepository()}).
 *
 * <p>Provides three services:
 * <ol>
 *   <li>{@link #streamToM2} — streams a downloaded artifact to a temp file computing SHA-256,
 *       SHA-1, and MD5 in one pass, then performs an atomic rename to the final path.</li>
 *   <li>{@link #writeMavenSidecars} — writes the {@code .sha1} and {@code .md5} files that Maven
 *       and Gradle expect alongside every artifact. Both use temp-then-rename for atomicity.</li>
 *   <li>{@link #writeRemoteRepositories} — writes (or appends to) the {@code _remote.repositories}
 *       hint file that tells Maven which remote repository an artifact came from, preventing
 *       unnecessary re-downloads when Maven re-evaluates repository configuration.</li>
 * </ol>
 *
 * <p>All methods are best-effort for steps 2 and 3 — an I/O failure in sidecar writing must never
 * fail a successful artifact download. Step 1 ({@link #streamToM2}) propagates I/O exceptions
 * because a failed stream means the artifact was not stored.
 *
 * <p>Atomic write strategy: every file is written to a {@code .part} temp sibling, then renamed
 * with {@link StandardCopyOption#ATOMIC_MOVE} and {@link StandardCopyOption#REPLACE_EXISTING}.
 * This ensures readers never see a partial file, and concurrent writers (jk + Maven, or two jk
 * processes) produce the same bytes (content-addressed idempotency for non-SNAPSHOT artifacts) so
 * last-writer-wins is harmless.
 */
public final class M2CompatWriter {

    private M2CompatWriter() {}

    /**
     * The result of {@link #streamToM2}: the three content hashes computed during streaming and the
     * artifact's byte size.
     */
    public record StreamResult(String sha256, String sha1, String md5, long size) {}

    /**
     * Stream {@code in} to {@code target} (creating parent directories as needed), computing
     * SHA-256, SHA-1, and MD5 in one pass. Uses a temp-then-rename write so readers always see a
     * complete file. Returns all three hashes and the total byte count.
     *
     * @throws IOException on any I/O failure; the temp file is deleted on error
     */
    public static StreamResult streamToM2(InputStream in, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest sha256 = newDigest("SHA-256");
        MessageDigest sha1 = newDigest("SHA-1");
        MessageDigest md5 = newDigest("MD5");
        long size = 0;
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha256.update(buf, 0, n);
                    sha1.update(buf, 0, n);
                    md5.update(buf, 0, n);
                    size += n;
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
        return new StreamResult(hex(sha256.digest()), hex(sha1.digest()), hex(md5.digest()), size);
    }

    /**
     * Write {@code .sha1} and {@code .md5} files alongside {@code artifact}. Maven clients refuse
     * to use local repository artifacts without matching sidecar checksums; Gradle also validates
     * them when {@code --verify-checksums} is active. Both files are written via temp-then-rename.
     * I/O errors are silently swallowed — sidecar failure must not fail the fetch.
     */
    public static void writeMavenSidecars(Path artifact, String sha1, String md5) {
        writeSidecar(artifact.resolveSibling(artifact.getFileName() + ".sha1"), sha1);
        writeSidecar(artifact.resolveSibling(artifact.getFileName() + ".md5"), md5);
    }

    /**
     * Write (or overwrite) the {@code _remote.repositories} file in {@code versionDir} to record
     * that {@code filename} was fetched from {@code repoName}. Format follows Maven Resolver's
     * convention: one {@code <filename>><repo-id>=} line per artifact. Best-effort; I/O errors are
     * swallowed. jk never reads this file — it is a courtesy hint for Maven and tools that inspect
     * {@code ~/.m2}.
     */
    public static void writeRemoteRepositories(Path versionDir, String repoName, String filename) {
        try {
            Path target = versionDir.resolve("_remote.repositories");
            // Build a minimal well-formed _remote.repositories entry.
            // The format tolerates multiple calls (e.g. JAR then POM); we rewrite the file each
            // time, which is fine because jk tracks provenance in repos/<name>/ sidecars instead.
            String content = "#NOTE: This is a jk-written provenance hint for Maven tooling.\n" + filename + ">"
                    + repoName + "=\n";
            Path tmp = versionDir.resolve("_remote.repositories.part");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    /**
     * Write {@code content} bytes to {@code target} atomically and return the Maven hashes.
     * Equivalent to {@link #copyToM2AndHash} but for in-memory content (e.g. a generated POM).
     */
    public static MavenHashes writeBytesToM2(byte[] content, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest sha1 = newDigest("SHA-1");
        MessageDigest md5 = newDigest("MD5");
        sha1.update(content);
        md5.update(content);
        try {
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e instanceof IOException ie ? ie : new IOException(e);
        }
        return new MavenHashes(hex(sha1.digest()), hex(md5.digest()));
    }

    /**
     * Maven-compatible hashes computed from an existing file: SHA-1 and MD5. Both are needed for
     * the {@code .sha1} / {@code .md5} sidecar files that Maven and Gradle expect.
     */
    public record MavenHashes(String sha1, String md5) {}

    /**
     * Copy {@code source} to {@code target} (atomic temp-rename), then compute and return the
     * SHA-1 and MD5 of the bytes. Used when the artifact is already in the CAS and we need to
     * mirror it into {@code ~/.m2}. Always a copy, never a hard link — jk doesn't control writes
     * to {@code ~/.m2}, and a hard link would risk silently corrupting the CAS-backed original if
     * something ever rewrote this file in place. I/O errors in the copy propagate; errors in
     * sidecar writing are swallowed.
     */
    public static MavenHashes copyToM2AndHash(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest sha1 = newDigest("SHA-1");
        MessageDigest md5 = newDigest("MD5");
        try {
            try (InputStream in = java.nio.file.Files.newInputStream(source);
                    OutputStream out = java.nio.file.Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha1.update(buf, 0, n);
                    md5.update(buf, 0, n);
                }
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e instanceof IOException ie ? ie : new IOException(e);
        }
        return new MavenHashes(hex(sha1.digest()), hex(md5.digest()));
    }

    // -------------------------------------------------------------------------

    private static void writeSidecar(Path sidecar, String hex) {
        try {
            Path tmp = sidecar.resolveSibling(sidecar.getFileName() + ".part");
            Files.writeString(tmp, hex, StandardCharsets.UTF_8);
            Files.move(tmp, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static MessageDigest newDigest(String algorithm) {
        return cc.jumpkick.util.Hashing.newDigest(algorithm);
    }

    private static String hex(byte[] digest) {
        return cc.jumpkick.util.Hashing.hex(digest);
    }
}
