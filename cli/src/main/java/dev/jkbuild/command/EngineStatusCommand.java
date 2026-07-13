// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.GlobalConfig;
import dev.jkbuild.engine.EnginePaths;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.plugin.protocol.Ndjson;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk engine status} — pings first (the same liveness authority every engine-aware command
 * uses, never a bare pidfile read) and reports pid/version/uptime/idle policy/active requests and
 * best-effort memory usage (heap used/committed/max, plus OS RSS where readable), or "not running"
 * with a non-zero exit so scripts can branch on it. {@code --output json} emits one flat object
 * instead, for scripts/CI that want to parse the result rather than scrape text ({@code -1} =
 * that number couldn't be observed).
 */
public final class EngineStatusCommand implements CliCommand {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show the build engine's status";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        EnginePaths.Paths paths = EnginePaths.current();
        Optional<EngineClient.Status> status = EngineClient.status(paths.socket());
        if (status.isEmpty()) {
            if (global.outputIsJson()) {
                CliOutput.out("{\"running\":false}");
            } else {
                CliOutput.out(GoalWedge.chipLine(
                        Glyphs.STOP, "Engine", GlobalConfig.nerdfont(), "Engine is not running"));
            }
            return Exit.FAILURE;
        }
        EngineClient.Status s = status.get();
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000);
        if (global.outputIsJson()) {
            CliOutput.out("{\"running\":true"
                    + ",\"pid\":" + s.pid()
                    + ",\"version\":" + Ndjson.quote(s.version())
                    + ",\"startedAt\":" + s.startedAtMillis()
                    + ",\"uptimeSeconds\":" + uptimeSeconds
                    + ",\"idleMinutes\":" + s.idleMinutes()
                    + ",\"activeRequests\":" + s.activeRequests()
                    + ",\"heapUsedBytes\":" + s.heapUsedBytes()
                    + ",\"heapCommittedBytes\":" + s.heapCommittedBytes()
                    + ",\"heapMaxBytes\":" + s.heapMaxBytes()
                    + ",\"rssBytes\":" + s.rssBytes()
                    + ",\"httpUrl\":" + (s.httpUrl() != null ? Ndjson.quote(s.httpUrl()) : "null")
                    + ",\"httpError\":" + (s.httpError() != null ? Ndjson.quote(s.httpError()) : "null")
                    + "}");
            return Exit.SUCCESS;
        }
        CliOutput.out("jk engine: running (pid " + s.pid() + ", version " + s.version() + ")");
        CliOutput.out("  uptime:          " + formatUptime(uptimeSeconds));
        CliOutput.out("  idle-minutes:    " + describeIdleMinutes(s));
        CliOutput.out("  active requests: " + s.activeRequests());
        String memory = formatMemory(s);
        if (memory != null) CliOutput.out("  memory:          " + memory);
        CliOutput.out("  http:            " + describeHttp(s, paths));
        return Exit.SUCCESS;
    }

    /**
     * The embedded HTTP server's state — serving URL, bind error, or disabled ({@code docs/http.md}).
     * The serving URL carries the bearer token as a fragment ({@code #t=…}) when the engine's
     * owner-only token file is readable: fragments never leave the browser, and this line is how
     * the dashboard SPA bootstraps its token — click/open the printed URL and it's authenticated.
     */
    private static String describeHttp(EngineClient.Status s, EnginePaths.Paths paths) {
        if (s.httpUrl() != null) {
            try {
                String token = java.nio.file.Files.readString(paths.httpToken()).trim();
                if (!token.isEmpty()) return s.httpUrl() + "#t=" + token;
            } catch (java.io.IOException e) {
                // token file unreadable/missing — the plain URL still serves static + loopback reads
            }
            return s.httpUrl();
        }
        if (s.httpError() != null) return "failed to start (" + s.httpError() + ")";
        return "disabled";
    }

    /**
     * One human line, e.g. {@code heap 18 MiB used / 42 MiB committed (max 256 MiB); rss 63 MiB}.
     * Unobservable parts are dropped; {@code null} when nothing at all was observed (an engine
     * predating the memory fields).
     */
    private static String formatMemory(EngineClient.Status s) {
        StringBuilder out = new StringBuilder();
        if (s.heapUsedBytes() >= 0 && s.heapCommittedBytes() >= 0) {
            out.append("heap ")
                    .append(mib(s.heapUsedBytes()))
                    .append(" used / ")
                    .append(mib(s.heapCommittedBytes()))
                    .append(" committed");
            if (s.heapMaxBytes() >= 0) {
                out.append(" (max ").append(mib(s.heapMaxBytes())).append(")");
            }
        }
        if (s.rssBytes() >= 0) {
            if (out.length() > 0) out.append("; ");
            out.append("rss ").append(mib(s.rssBytes()));
        }
        return out.length() > 0 ? out.toString() : null;
    }

    private static String mib(long bytes) {
        return (bytes + (1 << 19)) / (1 << 20) + " MiB"; // round to nearest MiB
    }

    private static String describeIdleMinutes(EngineClient.Status s) {
        // [http] forces never-self-terminate regardless of the configured value (docs/http.md) —
        // report the effective policy, not a number the engine isn't actually honoring.
        if (s.httpEnabled()) return "never ([http] enabled)";
        int idleMinutes = s.idleMinutes();
        if (idleMinutes == 0) return "0 (exits as soon as idle)";
        if (idleMinutes == -1) return "-1 (never expires)";
        return String.valueOf(idleMinutes);
    }

    private static String formatUptime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h + "h " + m + "m " + s + "s";
    }
}
