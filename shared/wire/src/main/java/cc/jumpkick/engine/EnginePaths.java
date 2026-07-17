// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine;

import cc.jumpkick.util.AtomicWrites;

import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
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

    // ---- generations + the endpoint pointer (engine-versioning-plan §2) --------------------

    /**
     * The endpoint pointer: a one-line file naming the CURRENT generation's socket file. Every
     * client resolves it before connecting; a new engine takes over by atomically replacing it.
     * Atomic single-file replace exists on every supported platform, unlike bound-socket rename
     * semantics — which is why the pointer exists at all.
     */
    public static Path endpoint(Paths paths) {
        return paths.dir().resolve(paths.key() + ".endpoint");
    }

    /** Generation {@code n}'s socket/lock/pid files ({@code <key>.gen<n>.sock} …). */
    public static Paths generation(Paths paths, int n) {
        String stem = paths.key() + ".gen" + n;
        Path dir = paths.dir();
        return new Paths(
                paths.key(),
                dir,
                dir.resolve(stem + ".sock"),
                dir.resolve(stem + ".lock"),
                dir.resolve(stem + ".pid"),
                paths.log(),
                // Token is generation-scoped so overlapping engines never clobber each other's
                // secret; clients find it by tokenFor(socket) naming convention either way.
                dir.resolve(stem + ".token"),
                paths.http(),
                paths.httpToken());
    }

    /**
     * The socket clients should connect to: the endpoint pointer's target when present, else the
     * legacy flat socket (pre-generation engines, and the symlink a generational engine leaves
     * there for older clients).
     */
    public static Path activeSocket(Paths paths) {
        Path ep = endpoint(paths);
        try {
            String name = java.nio.file.Files.readString(ep).trim();
            if (!name.isEmpty() && !name.contains("/") && !name.contains("\\")) {
                return paths.dir().resolve(name);
            }
        } catch (java.io.IOException ignored) {
            // No pointer → no engine. The flat path below is a never-bound placeholder (nothing
            // creates it since the legacy compat pointer was retired): probes against it fail
            // cleanly, which is exactly the "no engine running" answer.
        }
        return paths.socket();
    }

    /** Atomically point the endpoint at {@code socket} (a sibling of the engine dir). */
    public static void writeEndpoint(Paths paths, Path socket) throws java.io.IOException {
        Path ep = endpoint(paths);
        AtomicWrites.replace(ep, socket.getFileName().toString());
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
