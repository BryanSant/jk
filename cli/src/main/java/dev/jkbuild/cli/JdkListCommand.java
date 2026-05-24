// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.tui.Theme;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.IntellijJdkDir;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.resolver.Versions;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

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
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * {@code jk jdk list} — installed JDKs (under the IntelliJ JDK directory)
 * alongside the catalog entries available for download from the
 * JetBrains feed for the current OS / arch.
 *
 * <p>Renders a box-drawn table grouped by major version. Network failure
 * or {@code --offline} skips the catalog half — installed JDKs still
 * render, with whatever major info can be parsed from their identifier.
 */
@Command(name = "list", description = "List the available JDK installations")
public final class JdkListCommand implements Callable<Integer> {

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Option(names = "--offline",
            description = "Skip the JetBrains catalog fetch; show only installed JDKs.")
    boolean offline;

    @Option(names = "--feed-url", hidden = true,
            description = "Override the JetBrains JDK feed URL (for tests).")
    URI feedUrl;

    @Option(names = "--cache-file", hidden = true,
            description = "Override the catalog cache path (for tests).")
    Path cacheFile;

    /**
     * When true, AVAILABLE (catalog-only) rows are filtered out so the
     * table shows only DEFAULT + INSTALLED rows. Set programmatically by
     * {@link JdkInstalledCommand}; not exposed as a flag on {@code list}.
     */
    boolean installedOnly;

    enum Status {
        DEFAULT("default"),
        INSTALLED("installed"),
        AVAILABLE("");

        final String label;

        Status(String label) { this.label = label; }
    }

    /** One row in the rendered table. */
    record Row(int major, String vendor, String spec, Status status) {}

    @Override
    public Integer call() throws Exception {
        Path jdksRoot = jdksDir != null ? jdksDir : IntellijJdkDir.root();
        List<InstalledJdk> installed = new JdkRegistry(jdksRoot).list();
        Optional<String> defaultId = readDefaultIdentifier();
        JdkCatalog catalog = offline ? null : fetchCatalogOrNull();

        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();

        List<Row> rows = buildRows(installed, defaultId.orElse(null), catalog, os, arch);
        if (installedOnly) {
            rows = rows.stream()
                    .filter(r -> r.status() != Status.AVAILABLE)
                    .toList();
        }
        if (rows.isEmpty()) {
            String suffix = (installedOnly || offline) ? "" : ", no remote JDKs found";
            System.out.println("(no JDKs installed under " + jdksRoot + suffix + ")");
            return 0;
        }

        for (String line : renderTable(rows, os, arch)) {
            System.out.println(line);
        }
        return 0;
    }

    static List<Row> buildRows(
            List<InstalledJdk> installed,
            String defaultId,
            JdkCatalog catalog,
            String os,
            String arch) {
        // Index catalog entries by installFolderName, restricted to current host.
        Map<String, JdkCatalog.Entry> byInstall = new HashMap<>();
        if (catalog != null) {
            for (JdkCatalog.Entry e : catalog.entries()) {
                if (!e.os().equals(os) || !e.arch().equals(arch)) continue;
                byInstall.putIfAbsent(e.installFolderName(), e);
            }
        }

        // Installed → Row. Mark default first, then installed.
        List<Row> rows = new ArrayList<>();
        for (InstalledJdk j : installed) {
            JdkCatalog.Entry e = byInstall.get(j.identifier());
            String vendor = e != null ? e.vendor() + " " + e.product() : "";
            int major = e != null ? e.majorVersion() : parseMajor(j.identifier());
            Status status = j.identifier().equals(defaultId) ? Status.DEFAULT : Status.INSTALLED;
            rows.add(new Row(major, vendor, j.identifier(), status));
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
                        Status.AVAILABLE));
            }
        }

        // Sort: major desc, then status priority (default > installed > available),
        // then vendor alphabetical.
        rows.sort(Comparator
                .comparingInt(Row::major).reversed()
                .thenComparingInt((Row r) -> r.status().ordinal())
                .thenComparing(Row::vendor, Comparator.nullsLast(String::compareTo)));
        return rows;
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

    private static final String[] HEADERS = {"Version", "Vendor", "Spec", "Status"};

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
                out.add(dataRow(versionCell, r.vendor(), r.spec(), r.status(), widths));
            }
        }

        out.add(divider("╰", "┴", "╯", widths));                     // ╰─┴─╯ closes cols
        return out;
    }

    private static int[] computeWidths(List<Row> rows) {
        int[] w = new int[4];
        for (int i = 0; i < HEADERS.length; i++) w[i] = HEADERS[i].length();
        for (Row r : rows) {
            w[0] = Math.max(w[0], String.valueOf(r.major()).length());
            w[1] = Math.max(w[1], r.vendor() == null ? 0 : r.vendor().length());
            w[2] = Math.max(w[2], r.spec().length());
            w[3] = Math.max(w[3], r.status().label.length());
        }
        return w;
    }

    private static int innerWidth(int[] widths) {
        // 4 cells, each ` content ` (content + 2 padding), with 3 internal │ separators.
        int sum = 0;
        for (int c : widths) sum += c + 2;
        return sum + 3;
    }

    // We compose lines as raw Strings via Theme.colorize() (which wraps the
    // text in SGR codes manually) rather than going through
    // AttributedString.toAnsi() — the latter translates single-line
    // box-drawing chars (─ ┬ ┴ ├ ┤) into ASCII (- +) when no terminal is
    // supplied, which mangles this table.

    private static String border(String left, String mid, String right, int inner) {
        return Theme.colorize(left + mid.repeat(inner) + right, Theme.darkGray());
    }

    private static String divider(String left, String junction, String right, int[] widths) {
        var sb = new StringBuilder(left);
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? right : junction);
        }
        return Theme.colorize(sb.toString(), Theme.darkGray());
    }

    private static String titleLine(String title, int inner) {
        AttributedString gradient = Theme.gradientHeader(title);
        int total = Math.max(0, inner - gradient.length());
        int left = total / 2;
        int right = total - left;
        return Theme.colorize("│", Theme.darkGray())
                + " ".repeat(left)
                + perCharAnsi(gradient)
                + " ".repeat(right)
                + Theme.colorize("│", Theme.darkGray());
    }

    private static String headerRow(int[] widths) {
        var bar = Theme.colorize("│", Theme.darkGray());
        var sb = new StringBuilder(bar);
        for (int i = 0; i < HEADERS.length; i++) {
            sb.append(" ");
            sb.append(pad(HEADERS[i], widths[i], i == 0));
            sb.append(" ");
            sb.append(bar);
        }
        return sb.toString();
    }

    private static String dataRow(
            String version, String vendor, String spec, Status status, int[] widths) {
        var bar = Theme.colorize("│", Theme.darkGray());
        return bar
                + " " + center(version, widths[0]) + " " + bar
                + " " + padRight(vendor == null ? "" : vendor, widths[1]) + " " + bar
                + " " + Theme.colorize(padRight(spec, widths[2]), Theme.settled()) + " " + bar
                + " " + Theme.colorize(padRight(status.label, widths[3]), statusStyle(status)) + " " + bar;
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
            if (!sgr.isEmpty()) sb.append("\033[").append(sgr).append("m");
            sb.append(attr.charAt(i));
        }
        if (n > 0) sb.append("\033[0m");
        return sb.toString();
    }

    private static AttributedStyle statusStyle(Status status) {
        return switch (status) {
            case DEFAULT -> Theme.warning();
            case INSTALLED -> Theme.completedStep();
            case AVAILABLE -> AttributedStyle.DEFAULT;
        };
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

    private Optional<String> readDefaultIdentifier() {
        try {
            return GlobalDefaultJdk.current().currentIdentifier();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private JdkCatalog fetchCatalogOrNull() {
        if (!HostPlatform.supported()) return null;
        try {
            JdkCatalogClient client = feedUrl != null
                    ? new JdkCatalogClient(new Http(), feedUrl,
                            cacheFile != null ? cacheFile : ephemeralCachePath(),
                            java.time.Duration.ZERO)
                    : new JdkCatalogClient();
            return client.fetch();
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
