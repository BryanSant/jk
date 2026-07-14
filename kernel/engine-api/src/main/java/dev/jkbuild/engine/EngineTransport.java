// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Picks the engine's transport by OS: a Unix domain socket everywhere the design was built and
 * verified against (macOS/Linux, including under {@code native-image} — see {@code docs/engine.md}),
 * or loopback TCP plus a per-engine shared-secret token on Windows, where JDK NIO's {@code
 * StandardProtocolFamily.UNIX} support is newer and less consistently available across JDK/OS
 * version combinations. Kept behind this one seam so {@code EngineServer}/{@code EngineClient}'s
 * request handling — which only ever deals with an already-connected {@code SocketChannel} — never
 * needs to know which transport it's actually running over.
 *
 * <p>The token exists because a loopback TCP port, unlike a Unix domain socket file, isn't
 * filesystem-permission-gated by default — any local process could otherwise connect. The token
 * file itself relies on the OS's default new-file permissions (typically owner-only in a per-user
 * profile directory) for the same protection a {@code .sock} file's filesystem permissions give the
 * Unix path for free.
 */
public final class EngineTransport {

    private EngineTransport() {}

    /**
     * True on Windows — the one platform where the Unix-domain-socket path isn't used. Tests and
     * release smokes force either transport with {@code -Djk.engine.transport=tcp|unix} so the
     * TCP lane (auth handshake included) is exercisable off-Windows.
     */
    public static boolean useLoopbackTcp() {
        String forced = System.getProperty("jk.engine.transport", "");
        if ("tcp".equals(forced)) return true;
        if ("unix".equals(forced)) return false;
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** A fresh per-engine secret, URL-safe base64 — written to {@code paths.token()}, never logged. */
    public static String newToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
