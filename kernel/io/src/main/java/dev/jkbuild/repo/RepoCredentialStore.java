// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.credential.RepoCredential;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Stores artifact-repository credentials under {@code ~/.jk/repo-credentials/},
 * one file per repository id (the same id used in {@code jk.toml} and Maven's
 * {@code settings.xml}). Populated by {@code jk repo login}. Mirrors the forge
 * {@code TokenStore}: {@code 0600}/{@code 0700} on POSIX, best-effort on
 * Windows.
 *
 * <p>File format is line-based: first line is the scheme, then its fields.
 * <pre>
 *   bearer
 *   &lt;token&gt;
 *
 *   basic
 *   &lt;username&gt;
 *   &lt;password&gt;
 * </pre>
 */
public final class RepoCredentialStore {

    private final Path dir;

    public RepoCredentialStore() {
        this(JkDirs.home().resolve("repo-credentials"));
    }

    /** Visible for tests — point the store at a scratch directory. */
    public RepoCredentialStore(Path dir) {
        this.dir = dir;
    }

    public Optional<RepoCredential> read(String repoId) {
        Path file = fileFor(repoId);
        try {
            if (!Files.exists(file)) return Optional.empty();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return Optional.empty();
            String scheme = lines.get(0).strip().toLowerCase(Locale.ROOT);
            return switch (scheme) {
                case "bearer" ->
                    lines.size() >= 2 && !lines.get(1).isBlank()
                            ? Optional.of(new RepoCredential.Bearer(lines.get(1).strip()))
                            : Optional.empty();
                case "basic" ->
                    lines.size() >= 3
                            ? Optional.of(new RepoCredential.Basic(lines.get(1), lines.get(2)))
                            : Optional.empty();
                default -> Optional.empty();
            };
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void write(String repoId, RepoCredential cred) {
        String body =
                switch (cred) {
                    case RepoCredential.Bearer b -> "bearer\n" + b.token() + "\n";
                    case RepoCredential.Basic b -> "basic\n" + b.username() + "\n" + b.password() + "\n";
                    case RepoCredential.Anonymous ignored ->
                        throw new IllegalArgumentException("refusing to store an anonymous credential");
                };
        Path file = fileFor(repoId);
        try {
            Files.createDirectories(dir);
            trySetOwnerOnly(dir, "rwx------");
            Files.writeString(
                    file,
                    body,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            trySetOwnerOnly(file, "rw-------");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store credential for " + repoId, e);
        }
    }

    public void clear(String repoId) {
        try {
            Files.deleteIfExists(fileFor(repoId));
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private Path fileFor(String repoId) {
        return dir.resolve(sanitize(repoId));
    }

    /** Map a repo id to a safe single-path-segment filename. */
    static String sanitize(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (char c : id.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }

    private static void trySetOwnerOnly(Path path, String perms) {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return; // non-POSIX (Windows)
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
