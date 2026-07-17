// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * The one owner-only secret-file writer: writes {@code content} to {@code file} with {@code 0600}
 * permissions inside a {@code 0700} directory, so tokens and repository credentials are never
 * world-readable. On non-POSIX filesystems (Windows) the permission tightening is a best-effort
 * no-op — the file is still written. Shared by the forge {@code TokenStore} and the repository
 * {@code RepoCredentialStore} instead of each hand-rolling the same {@code chmod} dance.
 */
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {}

    /** Write {@code content} to {@code file} readable only by the owner, ensuring {@code dir} is {@code 0700}. */
    public static void write(Path dir, Path file, String content) throws IOException {
        Files.createDirectories(dir);
        setOwnerOnly(dir, "rwx------");
        Files.writeString(
                file,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        setOwnerOnly(file, "rw-------");
    }

    /** Best-effort tighten POSIX permissions on {@code path}; a no-op where POSIX perms are unsupported. */
    public static void setOwnerOnly(Path path, String perms) {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) return; // non-POSIX (Windows) — nothing to tighten
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
        } catch (IOException ignored) {
            // best-effort; the file still exists with default umask perms
        }
    }
}
