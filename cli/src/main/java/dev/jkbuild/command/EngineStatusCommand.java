// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Ansi;
import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.theme.Theme;
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
        CliOutput.out(GoalWedge.chipLine(
                Glyphs.PLAY, "Engine", GlobalConfig.nerdfont(), "Engine is running (pid " + pidStyled(s.pid()) + ")"));
        detail("Version", s.version());
        detail("Uptime", formatUptime(uptimeSeconds));
        detail("Jobs", String.valueOf(s.activePipelines()));
        String memory = formatMemory(s);
        if (memory != null) {
            detail("Memory", memory);
            String bar = memoryBar(s);
            if (bar != null) CliOutput.out(" ".repeat(VALUE_COL) + bar);
        }
        String http = describeHttp(s, paths);
        // OSC-8 hyperlink with the URL in the theme's path color so it reads AND behaves as a link.
        String webUi = s.httpUrl() != null ? Ansi.hyperlink(http, Theme.colorize(http, Theme.active().path())) : http;
        detail("Web UI", webUi);
        return Exit.SUCCESS;
    }

    /** Widest {@code "label:"} ({@code "Version:"}); values line up one space past it. */
    private static final int LABEL_FIELD = 8;

    /** Column where values (and the memory bar) begin: {@code " • "} + label field + one space. */
    private static final int VALUE_COL = 3 + LABEL_FIELD + 1;

    /** One detail line under the header: {@code  • Label:  value} (label left-aligned, values aligned). */
    private static void detail(String label, String value) {
        CliOutput.out(" " + Theme.colorize(Glyphs.BULLET, Theme.active().dim()) + " "
                + String.format("%-" + LABEL_FIELD + "s", label + ":") + " " + value);
    }

    /** The engine pid in yellow on an ANSI terminal (matching the start/stop wedges). */
    private static String pidStyled(long pid) {
        String s = Long.toString(pid);
        return Theme.active().isAnsi() ? Theme.colorize(s, Theme.active().warning()) : s;
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
            out.append("Heap ")
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
            out.append("RSS ").append(mib(s.rssBytes()));
        }
        return out.length() > 0 ? out.toString() : null;
    }

    private static String mib(long bytes) {
        return (bytes + (1 << 19)) / (1 << 20) + "M"; // round to nearest MiB
    }

    /**
     * A stacked heap bar aligned under the memory value: {@code used} in bright-cyan, {@code
     * committed}-beyond-used in cyan, and the rest (up to {@code max}) in bright-black. {@code null}
     * when the heap max isn't observable (nothing to scale against).
     */
    /** The committed-heap portion of the memory bar — a dim navy that recedes behind bright-cyan used. */
    private static final org.jline.utils.AttributedStyle COMMITTED_STYLE =
            org.jline.utils.AttributedStyle.DEFAULT.foreground(0x18, 0x3d, 0x64);

    private static String memoryBar(EngineClient.Status s) {
        long max = s.heapMaxBytes();
        if (max <= 0 || s.heapUsedBytes() < 0 || s.heapCommittedBytes() < 0) return null;
        int width = 50;
        int used = clamp((int) Math.round((double) s.heapUsedBytes() / max * width), 0, width);
        int committed = clamp((int) Math.round((double) s.heapCommittedBytes() / max * width), used, width);
        Theme t = Theme.active();
        return Theme.colorize("▰".repeat(used), t.brightCyan())
                + Theme.colorize("▰".repeat(committed - used), COMMITTED_STYLE)
                + Theme.colorize("▱".repeat(width - committed), t.darkGray());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String formatUptime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h + "h " + m + "m " + s + "s";
    }
}
