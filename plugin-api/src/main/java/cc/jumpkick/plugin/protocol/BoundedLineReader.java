// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * A {@link BufferedReader} whose {@link #readLine()} enforces the protocol's framing limits:
 *
 * <ul>
 *   <li><b>Line cap</b> — a peer that streams bytes without a newline must not buffer the
 *       process into an OOM; past {@link #DEFAULT_MAX_LINE} chars the read fails with
 *       {@link IOException} (malformed or hostile peer).
 *   <li><b>Idle timeout</b> (optional) — a streaming client blocked in {@code readLine()} against
 *       a dead engine must surface an error instead of hanging forever. When armed, a read that
 *       sees no bytes for the timeout closes {@code onTimeout} (the socket), which fails the
 *       blocked read. Servers pass no timeout: their connections legitimately idle between
 *       requests and during cancel-watches.
 * </ul>
 *
 * Drop-in for the bare {@code BufferedReader.readLine()} both sides of the engine socket used.
 */
public final class BoundedLineReader extends BufferedReader {

    /** Generous for real traffic (large dep graphs, long diagnostics); fatal for runaway peers. */
    public static final int DEFAULT_MAX_LINE = 64 * 1024 * 1024;

    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jk-protocol-idle-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final int maxLine;
    private final java.io.Closeable onTimeout;
    private final long idleTimeoutMillis;
    private volatile boolean timedOut;

    public BoundedLineReader(Reader in) {
        this(in, null, 0);
    }

    /** With an idle timeout: {@code onTimeout} (the socket/channel) is closed when a read stalls. */
    public BoundedLineReader(Reader in, java.io.Closeable onTimeout, long idleTimeoutMillis) {
        super(in);
        this.maxLine = DEFAULT_MAX_LINE;
        this.onTimeout = onTimeout;
        this.idleTimeoutMillis = idleTimeoutMillis;
    }

    @Override
    public String readLine() throws IOException {
        java.util.concurrent.ScheduledFuture<?> guard = null;
        if (onTimeout != null && idleTimeoutMillis > 0) {
            guard = WATCHDOG.schedule(
                    () -> {
                        timedOut = true;
                        try {
                            onTimeout.close();
                        } catch (IOException ignored) {
                        }
                    },
                    idleTimeoutMillis,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        try {
            StringBuilder line = new StringBuilder(128);
            boolean sawAny = false;
            while (true) {
                int r = read();
                if (r < 0) return sawAny ? line.toString() : null;
                sawAny = true;
                char c = (char) r;
                if (c == '\n') return line.toString();
                if (c == '\r') {
                    // \r or \r\n both terminate; peek-consume a following \n.
                    mark(1);
                    int n = read();
                    if (n >= 0 && n != '\n') reset();
                    return line.toString();
                }
                line.append(c);
                if (line.length() > maxLine) {
                    throw new IOException("protocol line exceeds " + maxLine + " chars without a terminator"
                            + " — refusing to buffer further (malformed or hostile peer)");
                }
            }
        } catch (IOException e) {
            if (timedOut) {
                throw new IOException(
                        "no protocol traffic for " + (idleTimeoutMillis / 60_000)
                                + " minutes — the engine looks dead (set JK_STREAM_IDLE_MINUTES to tune)",
                        e);
            }
            throw e;
        } finally {
            if (guard != null) guard.cancel(false);
        }
    }
}
