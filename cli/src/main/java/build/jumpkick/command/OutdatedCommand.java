// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.PathDisplay;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.engine.protocol.OutdatedReport;
import build.jumpkick.model.GitVersion;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.plugin.protocol.Ndjson;
import build.jumpkick.resolver.Versions;
import build.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.AttributedStyle;

/**
 * {@code jk outdated} — report declared dependencies whose repositories offer a newer version than
 * {@code jk.lock} pins. The read-only counterpart of {@code jk update}: {@code update} moves
 * versions, {@code outdated} only tells you what would move (and how far behind you are).
 *
 * <p>For every direct declared dependency the table shows {@code Current} (locked), {@code
 * Compatible} (newest the declared selector allows — what {@code jk update} would pick), and {@code
 * Latest} (newest stable overall). {@code --show-tip} adds a {@code Tip} column for the non-stable
 * frontier (SNAPSHOT/prerelease, or a git repo's moving HEAD). {@code --exclude-up-to-date} hides
 * rows already on the newest compatible version.
 *
 * <p>At a workspace root the report cascades over every module and a {@code Module} column appears.
 * Git dependencies enumerate remote tags (immutable repos ⇒ newer tags; a moving branch is the
 * tip). Coordinates with a known short catalog name render that name, italic.
 *
 * <p><b>Engine-hosted</b>: version enumeration links only engine-side, so the report is computed by
 * the resident engine over one read-only request — it writes nothing.
 */
public final class OutdatedCommand implements CliCommand {

    private boolean showTip;
    private boolean excludeUpToDate;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;

    @Override
    public String name() {
        return "outdated";
    }

    @Override
    public String description() {
        return "Report dependencies with newer versions available";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Add a Tip column for the non-stable frontier (prerelease / git HEAD).", "--show-tip"),
                Opt.flag("Hide dependencies already on the newest compatible version.", "--exclude-up-to-date"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url").hide(),
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide());
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}. A real {@code jk outdated} invocation always
     * engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunnerPlugin".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.showTip = in.isSet("show-tip");
        this.excludeUpToDate = in.isSet("exclude-up-to-date");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (!Files.exists(dir.resolve("jk.toml"))) {
            CliOutput.err("jk outdated: no jk.toml in " + PathDisplay.styledRaw(dir));
            return Exit.CONFIG;
        }
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        OutdatedReport report;
        if (engineDisabledForTests()) {
            report = build.jumpkick.cli.engine.InProcessEngine.require().outdatedInProcess(dir, cache, repoUrl);
        } else {
            report = build.jumpkick.cli.engine.EngineClient.runOutdated(
                    build.jumpkick.engine.EnginePaths.current(),
                    new build.jumpkick.cli.engine.EngineClient.OutdatedRequest(
                            dir, cache, repoUrl, global.offline, global.force));
        }

        if (report.error() != null) {
            CliOutput.err("jk outdated: " + report.error());
            return Exit.CONFIG;
        }

        List<OutdatedReport.Row> rows = report.rows();
        if (excludeUpToDate) {
            rows = rows.stream().filter(r -> !upToDate(r)).toList();
        }
        if (global.outputIsJson()) {
            CliOutput.outRaw(toJson(rows));
            return 0;
        }
        if (rows.isEmpty()) {
            CliOutput.out(excludeUpToDate ? "(no outdated dependencies)" : "(no dependencies to check)");
            return 0;
        }
        for (String line : renderTable(rows, report.workspace(), showTip, "Dependency versions")) {
            CliOutput.out(line);
        }
        return 0;
    }

    @Override
    public String toString() {
        return "outdated";
    }

    // ---------------------------------------------------------------
    // Version comparison (normalizes git tag names like "v1.2.3")
    // ---------------------------------------------------------------

    /** True when {@code a} is a strictly-higher version than {@code b} (both version-like). */
    private static boolean ahead(String a, String b) {
        String na = norm(a);
        String nb = norm(b);
        return na != null && nb != null && Versions.compare(na, nb) > 0;
    }

    /** Normalize a cell to a comparable Maven version, or null when it isn't one ("", "tip", tag text). */
    private static String norm(String v) {
        if (v == null || v.isEmpty() || v.equals("tip")) return null;
        String n = GitVersion.fromTag(v); // "v1.2.3" -> "1.2.3"; leaves Maven versions unchanged
        return (n.isEmpty() || !Character.isDigit(n.charAt(0))) ? null : n;
    }

    private static boolean upToDate(OutdatedReport.Row r) {
        if (norm(r.current()) == null) return false; // unlocked / unknown current — keep it visible
        return !ahead(r.compatible(), r.current()) && !ahead(r.latest(), r.current());
    }

    // ---------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------

    private static String toJson(List<OutdatedReport.Row> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            OutdatedReport.Row r = rows.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"module\":").append(Ndjson.quote(r.moduleLabel()))
                    .append(",\"dependency\":").append(Ndjson.quote(r.coordinate()))
                    .append(",\"display\":").append(Ndjson.quote(r.display()))
                    .append(",\"scope\":").append(Ndjson.quote(r.scope()))
                    .append(",\"current\":").append(Ndjson.quote(r.current()))
                    .append(",\"compatible\":").append(Ndjson.quote(r.compatible()))
                    .append(",\"latest\":").append(Ndjson.quote(r.latest()))
                    .append(",\"tip\":").append(Ndjson.quote(r.tip()))
                    .append('}');
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------
    // Rendering — box-drawn table mirroring JdkListCommand's style.
    // Columns are dynamic: [Module?] Dependency Current Compatible Latest [Tip?] Scope.
    // ---------------------------------------------------------------

    private static final String NONE = "—";

    static List<String> renderTable(List<OutdatedReport.Row> rows, boolean workspace, boolean showTip, String title) {
        List<String> headers = new ArrayList<>();
        if (workspace) headers.add("Module");
        headers.add("Dependency");
        headers.add("Current");
        headers.add("Compatible");
        headers.add("Latest");
        if (showTip) headers.add("Tip");
        headers.add("Scope");
        int n = headers.size();

        List<String[]> cellRows = new ArrayList<>();
        List<AttributedStyle[]> styleRows = new ArrayList<>();
        List<Boolean> dividerBefore = new ArrayList<>();
        String prevModule = null;
        boolean first = true;
        for (OutdatedReport.Row r : rows) {
            boolean newGroup = workspace && !r.moduleLabel().equals(prevModule);
            dividerBefore.add(!first && newGroup);
            String[] cells = new String[n];
            AttributedStyle[] styles = new AttributedStyle[n];
            int c = 0;
            if (workspace) {
                cells[c] = newGroup ? r.moduleLabel() : "";
                styles[c] = Theme.active().brightYellow();
                c++;
            }
            boolean hasShort = !r.display().isEmpty();
            cells[c] = hasShort ? r.display() : r.coordinate();
            styles[c] = hasShort ? Theme.active().path().italic() : Theme.active().path();
            c++;
            cells[c] = disp(r.current());
            styles[c] = null;
            c++;
            cells[c] = disp(r.compatible());
            styles[c] = ahead(r.compatible(), r.current()) ? Theme.active().brightYellow() : null;
            c++;
            cells[c] = disp(r.latest());
            styles[c] = ahead(r.latest(), r.compatible()) ? Theme.active().brightCyan() : null;
            c++;
            if (showTip) {
                cells[c] = disp(r.tip());
                styles[c] = Theme.active().darkGray();
                c++;
            }
            cells[c] = r.scope();
            styles[c] = Theme.active().darkGray();
            cellRows.add(cells);
            styleRows.add(styles);
            prevModule = r.moduleLabel();
            first = false;
        }

        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = headers.get(i).length();
        for (String[] cells : cellRows) {
            for (int i = 0; i < n; i++) w[i] = Math.max(w[i], cells[i].length());
        }
        int inner = innerWidth(w);

        List<String> out = new ArrayList<>();
        out.add(border("╭", "─", "╮", inner));
        out.add(titleLine(title, inner));
        out.add(divider("├", "┬", "┤", w));
        out.add(headerRow(headers, w));
        out.add(divider("├", "┼", "┤", w));
        for (int i = 0; i < cellRows.size(); i++) {
            if (dividerBefore.get(i)) out.add(divider("├", "┼", "┤", w));
            out.add(dataRow(cellRows.get(i), styleRows.get(i), w));
        }
        out.add(divider("╰", "┴", "╯", w));
        return out;
    }

    private static String disp(String v) {
        return v == null || v.isEmpty() ? NONE : v;
    }

    private static int innerWidth(int[] widths) {
        int sum = 0;
        for (int c : widths) sum += c + 2;
        return sum + (widths.length - 1);
    }

    private static String border(String left, String mid, String right, int inner) {
        if (!Theme.active().isAnsi()) return "+" + "-".repeat(inner) + "+";
        return Theme.colorize(left + mid.repeat(inner) + right, Theme.active().darkGray());
    }

    private static String divider(String left, String junction, String right, int[] widths) {
        boolean ansi = Theme.active().isAnsi();
        var sb = new StringBuilder(ansi ? left : "+");
        for (int i = 0; i < widths.length; i++) {
            sb.append((ansi ? "─" : "-").repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? (ansi ? right : "+") : (ansi ? junction : "+"));
        }
        return ansi ? Theme.colorize(sb.toString(), Theme.active().darkGray()) : sb.toString();
    }

    private static String titleLine(String title, int inner) {
        if (!Theme.active().isAnsi()) {
            int total = Math.max(0, inner - title.length());
            int left = total / 2;
            return "|" + " ".repeat(left) + title + " ".repeat(total - left) + "|";
        }
        int total = Math.max(0, inner - title.length());
        int left = total / 2;
        String banner = " ".repeat(left) + title + " ".repeat(total - left);
        String rail = Theme.colorize("│", Theme.active().darkGray());
        boolean nerdfont = build.jumpkick.config.GlobalConfig.nerdfont();
        if (nerdfont) {
            var chipColor = Theme.active().planBadgeColor();
            int availForBanner = Math.max(0, inner - 2);
            int pad = Math.max(0, availForBanner - title.length());
            String innerBanner = " ".repeat(pad / 2) + title + " ".repeat(pad - pad / 2);
            return rail
                    + Theme.colorize(build.jumpkick.cli.tui.Glyphs.PILL_LEFT_NERD, Theme.active().bright(chipColor))
                    + Theme.colorize(innerBanner, Theme.active().pipelineChip())
                    + Theme.colorize(build.jumpkick.cli.tui.Glyphs.PILL_RIGHT_NERD, Theme.active().bright(chipColor))
                    + rail;
        }
        return rail + Theme.colorize(banner, Theme.active().pipelineChip()) + rail;
    }

    private static String headerRow(List<String> headers, int[] widths) {
        String bar = Theme.active().isAnsi() ? Theme.colorize("│", Theme.active().darkGray()) : "|";
        var sb = new StringBuilder(bar);
        for (int i = 0; i < headers.size(); i++) {
            sb.append(" ").append(padRight(headers.get(i), widths[i])).append(" ").append(bar);
        }
        return sb.toString();
    }

    private static String dataRow(String[] cells, AttributedStyle[] styles, int[] widths) {
        String bar = Theme.active().isAnsi() ? Theme.colorize("│", Theme.active().darkGray()) : "|";
        var sb = new StringBuilder(bar);
        for (int i = 0; i < cells.length; i++) {
            sb.append(" ").append(styled(cells[i], widths[i], styles[i])).append(" ").append(bar);
        }
        return sb.toString();
    }

    private static String styled(String text, int width, AttributedStyle style) {
        String padded = padRight(text, width);
        if (style == null || !Theme.active().isAnsi()) return padded;
        return Theme.colorize(padded, style);
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
