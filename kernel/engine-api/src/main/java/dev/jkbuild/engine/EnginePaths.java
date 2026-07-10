// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine;

import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import java.nio.file.Path;

/**
 * Resolves the on-disk identity of the engine for a given {@code ~/.jk} state directory: a short key
 * derived from the resolved, absolute state-dir path, and the socket/lock/pid/log files under it.
 *
 * <p>Keying off the state dir (rather than a fixed machine-wide name) means a different {@code
 * JK_HOME}/{@code JK_STATE_DIR} naturally gets its own engine, and every invocation that resolves the
 * same state dir naturally shares one — see {@code docs/engine.md}.
 */
public final class EnginePaths {

    private EnginePaths() {}

    /** How many hex characters of the state-dir hash to use as the identity key. */
    private static final int KEY_LENGTH = 16;

    /**
     * The socket/lock/pid/log paths for one engine identity, all siblings under {@code engine/}.
     * {@code token} is only ever written/read on the {@link EngineTransport#useLoopbackTcp()} path
     * (Windows) — on the Unix-domain-socket path it's simply never created. {@code http} holds the
     * embedded HTTP server's actual bound URL and {@code httpToken} its bearer token (owner-only
     * permissions); both exist only while an engine with an enabled {@code [http]} table is serving
     * (see {@code docs/http.md}).
     */
    public record Paths(
            String key, Path dir, Path socket, Path lock, Path pid, Path log, Path token, Path http, Path httpToken) {}

    /** Resolve against the live {@link JkDirs} (honors {@code JK_HOME}/{@code JK_STATE_DIR}). */
    public static Paths current() {
        return resolve(JkDirs.state());
    }

    /** Resolve against an explicit state directory — the seam tests use. */
    public static Paths resolve(Path stateDir) {
        String key = keyFor(stateDir);
        Path dir = stateDir.resolve("engine");
        return new Paths(
                key,
                dir,
                dir.resolve(key + ".sock"),
                dir.resolve(key + ".lock"),
                dir.resolve(key + ".pid"),
                dir.resolve(key + ".log"),
                dir.resolve(key + ".token"),
                dir.resolve(key + ".http"),
                dir.resolve(key + ".http-token"));
    }

    /**
     * The token-file sibling of a {@code .sock} path, derived by naming convention alone — so the
     * CLI-side client's {@code connect(Path)} (which only ever receives {@code paths.socket()}, not
     * the full {@link Paths} record, across its several call sites) can find it without threading the
     * whole record through every method.
     */
    public static Path tokenFor(Path socket) {
        String name = socket.getFileName().toString();
        String base = name.endsWith(".sock") ? name.substring(0, name.length() - ".sock".length()) : name;
        return socket.resolveSibling(base + ".token");
    }

    /** A short, stable hash of the resolved absolute state-dir path. */
    static String keyFor(Path stateDir) {
        String hex = Hashing.sha256Hex(stateDir.toAbsolutePath().normalize().toString());
        return hex.substring(0, KEY_LENGTH);
    }
}
