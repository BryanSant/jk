// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;

/**
 * Persists forge tokens, keyed by host, under {@code ~/.jk/credentials/}.
 * One file per host (filename = sanitized host), each containing just the
 * token. This generalizes the original single-file {@code ~/.jk/github-token}
 * design to the multi-provider, multi-host world: a developer can be logged
 * into {@code github.com}, a private GHE, and {@code codeberg.org} at once.
 *
 * <p>The directory is created {@code rwx------} and each token file
 * {@code rw-------} on POSIX; on Windows the permission tightening is a
 * best-effort no-op (the user profile dir is already per-user).
 */
public final class TokenStore {

    private final Path dir;

    public TokenStore() {
        this(JkDirs.home().resolve("credentials"));
    }

    /** Visible for tests — point the store at a scratch directory. */
    public TokenStore(Path dir) {
        this.dir = dir;
    }

    public java.util.Optional<String> read(String host) {
        Path file = fileFor(host);
        try {
            if (!Files.exists(file)) return java.util.Optional.empty();
            String token = Files.readString(file, StandardCharsets.UTF_8).strip();
            return token.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(token);
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    public void write(String host, String token) {
        Path file = fileFor(host);
        try {
            Files.createDirectories(dir);
            trySetOwnerOnly(dir, "rwx------");
            Files.writeString(
                    file,
                    token,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            trySetOwnerOnly(file, "rw-------");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to store credential for " + host, e);
        }
    }

    public void clear(String host) {
        try {
            Files.deleteIfExists(fileFor(host));
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private Path fileFor(String host) {
        return dir.resolve(sanitize(ForgeKind.normalizeHost(host)));
    }

    /** Map a host to a safe single-path-segment filename. */
    static String sanitize(String host) {
        StringBuilder sb = new StringBuilder(host.length());
        for (char c : host.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-') ? c : '_');
        }
        return sb.toString();
    }

    private static void trySetOwnerOnly(Path path, String perms) {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return; // non-POSIX (Windows) — nothing to tighten
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (IOException ignored) {
            // best-effort; the file still exists with default umask perms
        }
    }
}
