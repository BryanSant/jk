// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.daemon;

import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;

/**
 * Resolves the on-disk identity of the daemon for a given {@code ~/.jk} state directory: a short key
 * derived from the resolved, absolute state-dir path, and the socket/lock/pid/log files under it.
 *
 * <p>Keying off the state dir (rather than a fixed machine-wide name) means a different {@code
 * JK_HOME}/{@code JK_STATE_DIR} naturally gets its own daemon, and every invocation that resolves the
 * same state dir naturally shares one — see {@code docs/daemon.md}.
 */
public final class DaemonPaths {

    private DaemonPaths() {}

    /** How many hex characters of the state-dir hash to use as the identity key. */
    private static final int KEY_LENGTH = 16;

    /** The socket/lock/pid/log paths for one daemon identity, all siblings under {@code daemon/}. */
    public record Paths(String key, Path dir, Path socket, Path lock, Path pid, Path log) {}

    /** Resolve against the live {@link JkDirs} (honors {@code JK_HOME}/{@code JK_STATE_DIR}). */
    public static Paths current() {
        return resolve(JkDirs.state());
    }

    /** Resolve against an explicit state directory — the seam tests use. */
    public static Paths resolve(Path stateDir) {
        String key = keyFor(stateDir);
        Path dir = stateDir.resolve("daemon");
        return new Paths(
                key,
                dir,
                dir.resolve(key + ".sock"),
                dir.resolve(key + ".lock"),
                dir.resolve(key + ".pid"),
                dir.resolve(key + ".log"));
    }

    /** A short, stable hash of the resolved absolute state-dir path. */
    static String keyFor(Path stateDir) {
        String hex = Hashing.sha256Hex(stateDir.toAbsolutePath().normalize().toString());
        return hex.substring(0, KEY_LENGTH);
    }
}
