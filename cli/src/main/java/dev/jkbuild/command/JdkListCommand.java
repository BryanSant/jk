// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Ansi;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.ActiveJavac;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkHit;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import dev.jkbuild.resolver.Versions;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * {@code jk jdk list} — every JDK the probe chain finds on this machine
 * (jk's managed dir, SDKMAN, JBang, mise, asdf, jenv, Homebrew, system
 * paths). With {@code --all}, also lists JDKs available for download from
 * the JetBrains feed for the current OS / arch.
 *
 * <p>The JDK {@code javac} on {@code PATH} resolves to is highlighted as
 * {@code current}; jk's global default is shown as {@code default} only when
 * it differs from the current one. Each row's source column names the tool
 * that owns the install ({@code sdkman}, {@code intellij}, …) rather than the
 * ephemeral {@code $JAVA_HOME} pointer.
 *
 * <p>Renders a box-drawn table grouped by major version. Without
 * {@code --all} the command is purely offline; with {@code --all},
 * network failure degrades to installed-only rows with a stderr warning.
 */
public final class JdkListCommand implements CliCommand {

    @Override public String name() { return "list"; }
    @Override public String description() { return "List installed JDKs (use --all to also show available ones)"; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.flag("Also list JDKs available for download from the JetBrains feed.", "--all"),
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir").hide(),
                Opt.value("<url>", "Override the JetBrains JDK feed URL (for tests).", "--feed-url").hide(),
                Opt.value("<file>", "Override the catalog cache path (for tests).", "--cache-file").hide());
    }

    boolean all;
    Path jdksDir;
    URI feedUrl;
    Path cacheFile;

    enum Status {
        // ACTIVE first so it sorts to the top of its version group and wins the
        // status-priority tie-break (it's also the primary role for styling when
        // a JDK holds several roles).
        ACTIVE("active"),
        DEFAULT("default"),
        NATIVE("native"),
        INSTALLED("installed"),
        AVAILABLE("available");

        final String label;

        Status(String label) { this.label = label; }
    }

    /**
     * One row in the rendered table. {@code status} is the primary role (for
     * sort + styling); {@code statusLabel} is the displayed text, which may be a
     * composite of roles a single JDK holds at once (e.g. {@code active/native}).
     */
    record Row(int major, String vendor, String spec, Status status, String statusLabel, String location) {}

    /** Build the composite status text from the roles a JDK holds. */
    private static String compositeLabel(boolean active, boolean isDefault, boolean isNative) {
        StringBuilder sb = new StringBuilder();
        if (active) sb.append("active");
        if (isDefault) { if (sb.length() > 0) sb.append('/'); sb.append("default"); }
        if (isNative) { if (sb.length() > 0) sb.append('/'); sb.append("native"); }
        return sb.length() == 0 ? "installed" : sb.toString();
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.all = in.isSet("all");
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.feedUrl = in.value("feed-url").map(URI::create).orElse(null);
        this.cacheFile = in.value("cache-file").map(Path::of).orElse(null);
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();
        Path jdksRoot = registry.jdksRoot();
        List<JdkHit> installed = registry.listHits();
        // Match the default / native rows by the recorded HOME path (unique per
        // install) rather than the vendor-major identifier (which two installs
        // under different roots can share).
        GlobalDefaultJdk gd = GlobalDefaultJdk.current();
        Path defaultHome = gd.defaultHome().orElse(null);
        Path graalHome = gd.graalHome().orElse(null);
        // Legacy configs recorded only the identifier (no home). Resolve it via
        // the registry — jk-managed installs win probe order, so exactly one row
        // is marked; a re-run of `jk jdk default` then records the exact home.
        try {
            if (defaultHome == null) {
                defaultHome = gd.currentIdentifier().flatMap(id -> findHome(registry, id)).orElse(null);
            }
            if (graalHome == null) {
                graalHome = gd.graalIdentifier().flatMap(id -> findHome(registry, id)).orElse(null);
            }
        } catch (IOException ignored) {
            // malformed config — leave both null (no row marked)
        }
        // Catalog (and therefore the network fetch) is only consulted when the
        // user opts in to "available" rows via --all. Default `list` is a
        // pure offline view of what's on disk.
        JdkCatalog catalog = all ? fetchCatalogOrNull() : null;

        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();

        // The "current" JDK is whatever `javac` on PATH resolves to — what this
        // shell actually compiles with, independent of jk's default pointer.
        Path currentHome = ActiveJavac.home().orElse(null);

        List<Row> rows = buildRows(installed, defaultHome, catalog, os, arch, currentHome, graalHome);
        if (!all) {
            rows = rows.stream()
                    .filter(r -> r.status() != Status.AVAILABLE)
                    .toList();
        }
        if (rows.isEmpty()) {
            String suffix = all ? ", no remote JDKs found" : "";
            System.out.println("(no JDKs installed under " + jdksRoot + suffix + ")");
            return 0;
        }

        for (String line : renderTable(rows, os, arch)) {
            System.out.println(line);
        }
        return 0;
    }

    @Override public String toString() { return "jdk list"; }

    static List<Row> buildRows(
            List<JdkHit> installed,
            Path defaultHome,
            JdkCatalog catalog,
            String os,
            String arch,
            Path currentHome,
            Path graalHome) {
        // Index catalog entries by installFolderName, restricted to current host.
        Map<String, JdkCatalog.Entry> byInstall = new HashMap<>();
        if (catalog != null) {
            for (JdkCatalog.Entry e : catalog.entries()) {
                if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
                byInstall.putIfAbsent(e.installFolderName(), e);
            }
        }

        // Installed → Row. Status precedence: CURRENT (what `javac` on PATH
        // resolves to) wins over DEFAULT (jk's global default) — so the green
        // "default" row only appears when the default JDK isn't the one on PATH.
        boolean currentShown = false;
        List<Row> rows = new ArrayList<>();
        for (JdkHit j : installed) {
            String id = IntellijJdkDir.installDirOf(j.home()).getFileName().toString();
            JdkCatalog.Entry e = byInstall.get(id);
            // Catalog match → catalog's display strings. No match → fall back to the
            // probe's vendor lookup so external installs (SDKMAN, system, mise, …)
            // still get a useful vendor column.
            String vendor = e != null
                    ? e.vendor() + " " + e.product()
                    : (j.vendor() != JdkVendor.UNKNOWN ? j.vendor().displayName() : "");
            int major = e != null ? e.majorVersion() : parseMajor(id);
            boolean isActive = sameHome(currentHome, j.home());
            boolean isDefault = sameHome(defaultHome, j.home());
            boolean isNative = sameHome(graalHome, j.home());
            // A JDK can hold several roles at once; status is the primary (for
            // sort/style), statusLabel the composite shown to the user.
            Status status = isActive ? Status.ACTIVE
                    : isDefault ? Status.DEFAULT
                    : isNative ? Status.NATIVE
                    : Status.INSTALLED;
            if (isActive) currentShown = true;
            rows.add(new Row(major, vendor, id, status,
                    compositeLabel(isActive, isDefault, isNative), j.source()));
        }

        // The active javac may resolve to a JDK no probe surfaced (e.g. on PATH
        // but outside every manager's root). Synthesize an ACTIVE row so the
        // JDK this shell actually uses is never absent from the list.
        if (currentHome != null && !currentShown) {
            dev.jkbuild.discovery.ProbeSupport.discoverJdk(currentHome, "path").ifPresent(hit -> {
                String id = IntellijJdkDir.installDirOf(hit.home()).getFileName().toString();
                String vendor = hit.vendor() != JdkVendor.UNKNOWN ? hit.vendor().displayName() : "";
                boolean d = sameHome(defaultHome, hit.home());
                boolean n = sameHome(graalHome, hit.home());
                rows.add(new Row(parseMajor(id), vendor, id, Status.ACTIVE,
                        compositeLabel(true, d, n), hit.source()));
            });
        }

        // Catalog → Row for each (vendor, product, major) not already installed.
        // Pick the latest non-preview entry per tuple.
        if (catalog != null) {
            var installedKeys = new java.util.HashSet<String>();
            for (Row r : rows) {
                installedKeys.add(r.vendor() + "|" + r.major());
            }
            Map<String, JdkCatalog.Entry> latestPerTuple = new LinkedHashMap<>();
            for (JdkCatalog.Entry e : catalog.entries()) {
                if (e.preview()) continue;
                if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
                String label = e.vendor() + " " + e.product();
                String key = label + "|" + e.majorVersion();
                if (installedKeys.contains(key)) continue;
                JdkCatalog.Entry prior = latestPerTuple.get(key);
                if (prior == null || Versions.compare(e.version(), prior.version()) > 0) {
                    latestPerTuple.put(key, e);
                }
            }
            for (JdkCatalog.Entry e : latestPerTuple.values()) {
                rows.add(new Row(
                        e.majorVersion(),
                        e.vendor() + " " + e.product(),
                        e.installFolderName(),
                        Status.AVAILABLE,
                        "available",
                        "download"));
            }
        }

        // Sort: major desc, then status priority (current > default > installed
        // > available, via enum ordinal), then vendor alphabetical.
        rows.sort(Comparator
                .comparingInt(Row::major).reversed()
                .thenComparingInt((Row r) -> r.status().ordinal())
                .thenComparing(Row::vendor, Comparator.nullsLast(String::compareTo)));
        return rows;
    }

    /**
     * True when {@code currentHome} and a probe-discovered {@code hitHome}
     * point at the same JDK. Both are normally already canonical, but we
     * canonicalise defensively (each may be a symlink path) before comparing.
     */
    /** Resolve an install identifier to its home via the registry (first match), or empty. */
    private static Optional<Path> findHome(JdkRegistry registry, String id) {
        try {
            return registry.find(id).map(InstalledJdk::home);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean sameHome(Path currentHome, Path hitHome) {
        if (currentHome == null || hitHome == null) return false;
        return canonical(currentHome).equals(canonical(hitHome));
    }

    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /**
     * Extract the major version from an install identifier when no catalog
     * match is available. Handles modern names ({@code temurin-25.0.3} → 25,
     * {@code graalvm-jdk-21} → 21) and the legacy Java-8 form
     * ({@code temurin-1.8.0_492} → 8). Returns 0 when no number is found.
     */
    static int parseMajor(String identifier) {
        var m = Pattern.compile("(\\d+)(?:[._](\\d+))?").matcher(identifier);
        if (!m.find()) return 0;
        int first = Integer.parseInt(m.group(1));
        if (first == 1 && m.group(2) != null) {
            return Integer.parseInt(m.group(2));
        }
        return first;
    }

    // ---------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------

    private static final String[] HEADERS = {"Version", "Vendor", "Spec", "Status", "Source"};

    /** Render the table as a sequence of ANSI-styled lines, ready to println. */
    static List<String> renderTable(List<Row> rows, String os, String arch) {
        int[] widths = computeWidths(rows);
        int inner = innerWidth(widths);
        String title = "Jk - Available JDKs for "
                + HostPlatform.displayOs(os) + " " + HostPlatform.displayArch(arch);

        List<String> out = new ArrayList<>();
        out.add(border("╭", "─", "╮", inner));                       // ╭───╮
        out.add(titleLine(title, inner));                                            // │ <gradient> │
        out.add(divider("├", "┬", "┤", widths));                     // ├─┬─┤
        out.add(headerRow(widths));                                                  // │ Version │ ...
        out.add(divider("├", "┼", "┤", widths));                     // ├─┼─┤

        // Group rows by major, in their already-sorted order.
        Map<Integer, List<Row>> grouped = new TreeMap<>(Comparator.reverseOrder());
        for (Row r : rows) grouped.computeIfAbsent(r.major(), k -> new ArrayList<>()).add(r);

        boolean firstGroup = true;
        for (var entry : grouped.entrySet()) {
            if (!firstGroup) {
                out.add(divider("├", "┼", "┤", widths));             // ├─┼─┤ between groups
            }
            firstGroup = false;
            var groupRows = entry.getValue();
            for (int i = 0; i < groupRows.size(); i++) {
                Row r = groupRows.get(i);
                String versionCell = (i == 0) ? String.valueOf(r.major()) : "";
                out.add(dataRow(versionCell, r, widths));
            }
        }

        out.add(divider("╰", "┴", "╯", widths));                     // ╰─┴─╯ closes cols
        return out;
    }

    private static int[] computeWidths(List<Row> rows) {
        int[] w = new int[5];
        for (int i = 0; i < HEADERS.length; i++) w[i] = HEADERS[i].length();
        for (Row r : rows) {
            w[0] = Math.max(w[0], String.valueOf(r.major()).length());
            w[1] = Math.max(w[1], r.vendor() == null ? 0 : r.vendor().length());
            w[2] = Math.max(w[2], r.spec().length());
            w[3] = Math.max(w[3], r.statusLabel().length());
            w[4] = Math.max(w[4], r.location() == null ? 0 : r.location().length());
        }
        return w;
    }

    private static int innerWidth(int[] widths) {
        // 5 cells, each ` content ` (content + 2 padding), with 4 internal │ separators.
        int sum = 0;
        for (int c : widths) sum += c + 2;
        return sum + 4;
    }

    // We compose lines as raw Strings via Theme.colorize() (which wraps the
    // text in SGR codes manually) rather than going through
    // AttributedString.toAnsi() — the latter translates single-line
    // box-drawing chars (─ ┬ ┴ ├ ┤) into ASCII (- +) when no terminal is
    // supplied, which mangles this table.

    private static String border(String left, String mid, String right, int inner) {
        return Theme.colorize(left + mid.repeat(inner) + right, Theme.active().darkGray());
    }

    private static String divider(String left, String junction, String right, int[] widths) {
        var sb = new StringBuilder(left);
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? right : junction);
        }
        return Theme.colorize(sb.toString(), Theme.active().darkGray());
    }

    private static String titleLine(String title, int inner) {
        AttributedString gradient = Theme.active().gradientHeader(title);
        int total = Math.max(0, inner - gradient.length());
        int left = total / 2;
        int right = total - left;
        return Theme.colorize("│", Theme.active().darkGray())
                + " ".repeat(left)
                + perCharAnsi(gradient)
                + " ".repeat(right)
                + Theme.colorize("│", Theme.active().darkGray());
    }

    private static String headerRow(int[] widths) {
        var bar = Theme.colorize("│", Theme.active().darkGray());
        var sb = new StringBuilder(bar);
        for (int i = 0; i < HEADERS.length; i++) {
            sb.append(" ");
            sb.append(pad(HEADERS[i], widths[i], i == 0));
            sb.append(" ");
            sb.append(bar);
        }
        return sb.toString();
    }

    private static String dataRow(String version, Row r, int[] widths) {
        var bar = Theme.colorize("│", Theme.active().darkGray());
        Status status = r.status();
        String location = r.location();
        String locStyled;
        if (location == null || location.isEmpty()) {
            locStyled = padRight("", widths[4]);
        } else {
            // AVAILABLE rows render the source ("download") in the same dark-gray
            // as their status, so the entire catalog-only row reads as de-emphasised
            // relative to actually-installed JDKs.
            AttributedStyle locStyle = status == Status.AVAILABLE ? Theme.active().darkGray() : Theme.active().warning();
            locStyled = Theme.colorize(location, locStyle) + " ".repeat(widths[4] - location.length());
        }
        // The active JDK gets its vendor + spec bolded so the one in effect stands out.
        boolean active = status == Status.ACTIVE;
        String vendor = r.vendor() == null ? "" : r.vendor();
        String vendorCell = active
                ? Theme.colorize(padRight(vendor, widths[1]), AttributedStyle.DEFAULT.bold())
                : padRight(vendor, widths[1]);
        AttributedStyle specStyle = active ? Theme.active().settled().bold() : Theme.active().settled();
        String specCell = Theme.colorize(padRight(r.spec(), widths[2]), specStyle);
        return bar
                + " " + center(version, widths[0]) + " " + bar
                + " " + vendorCell + " " + bar
                + " " + specCell + " " + bar
                + " " + statusCell(r.statusLabel(), widths[3]) + " " + bar
                + " " + locStyled + " " + bar;
    }

    /**
     * Render the (possibly composite) status label with a distinct color per
     * role — active = bright-cyan+bold, default = bright-yellow, native =
     * bright-green — joined by a dim slash, then padded to the column width.
     */
    private static String statusCell(String label, int width) {
        String[] parts = label.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(Theme.colorize("/", Theme.active().darkGray()));
            sb.append(Theme.colorize(parts[i], segmentStyle(parts[i])));
        }
        int pad = width - label.length();
        if (pad > 0) sb.append(" ".repeat(pad));
        return sb.toString();
    }

    private static AttributedStyle segmentStyle(String role) {
        return switch (role) {
            case "active" -> Theme.active().brightCyan().bold();
            case "default" -> Theme.active().brightYellow();
            case "native" -> Theme.active().brightGreen();
            case "available" -> Theme.active().darkGray();
            default -> Theme.active().completedStep();   // "installed"
        };
    }

    /**
     * Walk an AttributedString and emit per-codepoint SGR escapes inline.
     * Used for the gradient title: each character carries its own color,
     * so we re-issue the SGR prefix on every step. The unicode characters
     * pass through untouched (no JLine ACS translation).
     */
    private static String perCharAnsi(AttributedString attr) {
        var sb = new StringBuilder();
        int n = attr.length();
        for (int i = 0; i < n; i++) {
            String sgr = attr.styleAt(i).toAnsi();
            if (!sgr.isEmpty()) sb.append(Ansi.CSI).append(sgr).append("m");
            sb.append(attr.charAt(i));
        }
        if (n > 0) sb.append(Ansi.RESET);
        return sb.toString();
    }

    private static String pad(String s, int width, boolean center) {
        return center ? center(s, width) : padRight(s, width);
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    private static String center(String s, int width) {
        if (s.length() >= width) return s;
        int total = width - s.length();
        int left = total / 2;
        int right = total - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }

    // ---------------------------------------------------------------
    // Helpers / catalog plumbing
    // ---------------------------------------------------------------


    private JdkCatalog fetchCatalogOrNull() {
        if (!HostPlatform.supported()) return null;
        try {
            boolean noCache = dev.jkbuild.config.ActiveConfig.get().noCacheOr(false);
            JdkCatalogClient client = (feedUrl != null
                    ? new JdkCatalogClient(new Http(), feedUrl,
                            cacheFile != null ? cacheFile : ephemeralCachePath(),
                            java.time.Duration.ZERO)
                    : new JdkCatalogClient())
                    .onWarning(System.err::println);
            // --all is the "show me everything" view: every vendor/product at
            // every major >= 17, not just jk's curated LTS-or-latest set.
            return client.fetch(noCache, /* firstClassOnly = */ false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("jk jdk list: JetBrains feed unreachable ("
                    + e.getMessage() + "); showing installed JDKs only.");
            return null;
        }
    }

    private static Path ephemeralCachePath() throws IOException {
        Path tmp = java.nio.file.Files.createTempFile("jk-feed-", ".json.xz");
        tmp.toFile().deleteOnExit();
        java.nio.file.Files.delete(tmp);
        return tmp;
    }
}
