// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.repo.JkMavenLocalRepo;
import dev.jkbuild.resolver.Versions;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** {@code jk cache} — manage the on-disk cache at {@code $JK_CACHE_DIR}. */
public final class CacheCommand implements CliCommand {

    @Override
    public String name() {
        return "cache";
    }

    @Override
    public String description() {
        return "Manage the jk download / action cache";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(
                new CacheDirCommand(),
                new CacheInfoCommand(),
                new CacheSearchCommand(),
                new CachePruneCommand(),
                new CachePurgeCommand());
    }

    @Override
    public int run(Invocation in) {
        return 64;
    }

    // --- shared helpers (accessed by Cache*Command classes) ---------------------------

    static Path resolveCacheRoot(Path override) {
        return override != null ? override : JkDirs.cache();
    }

    record Stats(long files, long bytes) {}

    static Stats statsOf(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return new Stats(0, 0);
        long files = 0, bytes = 0;
        try (var stream = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p)) continue;
                files++;
                bytes += Files.size(p);
            }
        }
        return new Stats(files, bytes);
    }

    static void deleteContents(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(root))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    static String fmtCount(long n) {
        return String.format("%,d", n);
    }

    static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int unit = -1;
        do {
            v /= 1024.0;
            unit++;
        } while (v >= 1024.0 && unit < units.length - 1);
        return String.format("%.1f %s", v, units[unit]);
    }

    /**
     * Compact size for tight table cells: {@code 545.3M}, {@code 30.8M}, {@code 1.2G} (1024-based).
     */
    static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        String units = "KMGT";
        double v = bytes;
        int u = -1;
        do {
            v /= 1024.0;
            u++;
        } while (v >= 1024.0 && u < units.length() - 1);
        return String.format("%.1f%s", v, units.charAt(u));
    }

    // --- subcommands defined here to access private helpers ----------------------

    public static final class CacheDirCommand implements CliCommand {
        @Override
        public String name() {
            return "dir";
        }

        @Override
        public String description() {
            return "Print the cache directory path";
        }

        @Override
        public List<Opt> options() {
            return List.of(Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                    .hide());
        }

        @Override
        public int run(Invocation in) {
            System.out.println(
                    resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null)));
            return 0;
        }
    }

    public static final class CacheInfoCommand implements CliCommand {
        @Override
        public String name() {
            return "info";
        }

        @Override
        public String description() {
            return "Show cache size and contents";
        }

        @Override
        public List<Opt> options() {
            return List.of(Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                    .hide());
        }

        private static final String[] HEADERS = {"Metric", "File Count", "Storage Size"};

        @Override
        public int run(Invocation in) throws IOException {
            Path root = resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null));
            if (!Files.isDirectory(root)) {
                System.out.println(
                        "Cache directory: " + dev.jkbuild.cli.PathDisplay.styledRaw(root) + " (not yet created)");
                return 0;
            }
            Stats sha = statsOf(root.resolve("sha256"));
            Stats actions = statsOf(root.resolve("actions"));
            Stats repos = statsOf(root.resolve("repos"));
            Stats runs = statsOf(root.resolve("runs"));
            Stats stamps = statsOf(root.resolve("format-stamps"));
            long totalFiles = sha.files + actions.files + repos.files + runs.files + stamps.files;
            long totalBytes = sha.bytes + actions.bytes + repos.bytes + runs.bytes + stamps.bytes;

            // Utilization denominator: the configured LRU ceiling ([cache]
            // max-size-gb in ~/.jk/config.toml), or the documented 20 GiB default
            // when unset, so the bar is always meaningful.
            var cfg = dev.jkbuild.config.JkCacheConfig.resolve();
            long maxBytes = (long) cfg.maxSizeGb().orElse(20) * 1024L * 1024L * 1024L;

            // Last-pruned timestamp from the scheduler stamp file.
            String lastPruned = lastPrunedLabel(root);

            for (String line : renderInfoTable(sha, actions, repos, runs, stamps,
                    totalFiles, totalBytes, maxBytes, lastPruned)) {
                System.out.println(line);
            }
            return 0;
        }

        private static String lastPrunedLabel(Path root) {
            Path stamp = root.resolve(dev.jkbuild.task.CachePruneScheduler.LAST_PRUNED_FILE);
            if (!Files.isRegularFile(stamp)) return "never";
            try {
                long millis = Long.parseLong(Files.readString(stamp, StandardCharsets.UTF_8).trim());
                long ageMs = System.currentTimeMillis() - millis;
                long days = ageMs / (24L * 60 * 60 * 1000);
                if (days == 0) return "today";
                if (days == 1) return "1 day ago";
                return days + " days ago";
            } catch (Exception e) {
                return "unknown";
            }
        }

        private static List<String> renderInfoTable(
                Stats sha, Stats actions, Stats repos, Stats runs, Stats stamps,
                long totalFiles, long totalBytes, long maxBytes, String lastPruned) {
            String[][] rows = {
                {"CAS Blobs",     fmtCount(sha.files),     fmtSize(sha.bytes)},
                {"Action Cache",  fmtCount(actions.files), fmtSize(actions.bytes)},
                {"Worker JARs",   fmtCount(repos.files),   fmtSize(repos.bytes)},
                {"Run Logs",      fmtCount(runs.files),    fmtSize(runs.bytes)},
                {"Format Stamps", fmtCount(stamps.files),  fmtSize(stamps.bytes)},
            };
            String[] total = {"Total", fmtCount(totalFiles), fmtSize(totalBytes)};

            int[] w = new int[3];
            for (int i = 0; i < 3; i++) w[i] = HEADERS[i].length();
            for (String[] r : rows) for (int i = 0; i < 3; i++) w[i] = Math.max(w[i], r[i].length());
            for (int i = 0; i < 3; i++) w[i] = Math.max(w[i], total[i].length());
            int inner = (w[0] + 2) + (w[1] + 2) + (w[2] + 2) + 2;

            List<String> out = new ArrayList<>();
            out.add(border("╭", "╮", inner));
            out.add(titleRow("Cache Directory Information", inner));
            out.add(divider("├", "┬", "┤", w));
            out.add(headerRow(w));
            out.add(divider("├", "┼", "┤", w));
            for (String[] r : rows) out.add(metricRow(r, w));
            out.add(divider("├", "┼", "┤", w));
            out.add(metricRow(total, w));
            out.add(divider("├", "┴", "┤", w)); // ┴ closes the columns; utilization spans full width
            out.add(utilizationRow(totalBytes, maxBytes, inner));
            out.add(border("╰", "╯", inner));
            Theme t = Theme.active();
            out.add("  Last pruned: " + Theme.colorize(lastPruned, "never".equals(lastPruned)
                    ? t.warning() : t.normalGray()));
            return out;
        }

        private static String border(String left, String right, int inner) {
            return Theme.colorize(
                    left + "─".repeat(inner) + right, Theme.active().darkGray());
        }

        private static String divider(String left, String junction, String right, int[] w) {
            StringBuilder sb = new StringBuilder(left);
            for (int i = 0; i < w.length; i++) {
                sb.append("─".repeat(w[i] + 2));
                sb.append(i == w.length - 1 ? right : junction);
            }
            return Theme.colorize(sb.toString(), Theme.active().darkGray());
        }

        /**
         * Full-width title band — white text on plan-blue background, with Nerd Font
         * rounded pill caps (U+E0B6 / U+E0B4) in plan-blue foreground between the │ rails.
         * The │ rails are retained; the caps sit inside them, consuming one column each.
         */
        private static String titleRow(String title, int inner) {
            Theme t = Theme.active();
            boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
            String rail = Theme.colorize("│", t.darkGray());
            String leftCap  = nerdfont ? Theme.colorize(dev.jkbuild.cli.tui.Glyphs.PILL_LEFT_NERD,  t.bright(t.planBadgeColor())) : "";
            String rightCap = nerdfont ? Theme.colorize(dev.jkbuild.cli.tui.Glyphs.PILL_RIGHT_NERD, t.bright(t.planBadgeColor())) : "";
            // Band fills inner minus the two cap columns (or full inner when no Nerd Font).
            int bandWidth = inner - (nerdfont ? 2 : 0);
            int pad = Math.max(0, bandWidth - title.length());
            int left = pad / 2, right = pad - left;
            String band = Theme.colorize(
                    " ".repeat(left) + title + " ".repeat(right), t.planBadge());
            return rail + leftCap + band + rightCap + rail;
        }

        /** Column headers, left-justified, in white. */
        private static String headerRow(int[] w) {
            String bar = Theme.colorize("│", Theme.active().darkGray());
            StringBuilder sb = new StringBuilder(bar);
            for (int i = 0; i < HEADERS.length; i++) {
                sb.append(" ")
                        .append(Theme.colorize(
                                padRight(HEADERS[i], w[i]), Theme.active().brightWhite()))
                        .append(" ")
                        .append(bar);
            }
            return sb.toString();
        }

        /** A metric row: first column right-justified + white, the rest plain + left-justified. */
        private static String metricRow(String[] r, int[] w) {
            String bar = Theme.colorize("│", Theme.active().darkGray());
            return bar
                    + " "
                    + Theme.colorize(padLeft(r[0], w[0]), Theme.active().brightWhite())
                    + " "
                    + bar
                    + " "
                    + padRight(r[1], w[1])
                    + " "
                    + bar
                    + " "
                    + padRight(r[2], w[2])
                    + " "
                    + bar;
        }

        /** Full-width "Utilization <bar> NN%" row. */
        private static String utilizationRow(long used, long max, int inner) {
            Theme t = Theme.active();
            int pct = (int) Math.round(dev.jkbuild.cli.tui.ProgressBar.fraction(used, max) * 100);
            String prefix = " Utilization  ";
            String suffix = "  " + pct + "% ";
            int barWidth = Math.max(0, inner - prefix.length() - suffix.length());
            String bar = dev.jkbuild.cli.tui.ProgressBar.renderBar(
                    used, max, barWidth,
                    t.bright(t.planBadgeColor()), // filled ▰ — plan-badge blue
                    t.darkGray());                 // empty  ▱ — bright-black
            String rail = Theme.colorize("│", t.darkGray());
            return rail + prefix + bar + suffix + rail;
        }

        private static String padRight(String s, int w) {
            return s.length() >= w ? s : s + " ".repeat(w - s.length());
        }

        private static String padLeft(String s, int w) {
            return s.length() >= w ? s : " ".repeat(w - s.length()) + s;
        }
    }

    public static final class CacheSearchCommand implements CliCommand {
        @Override
        public String name() {
            return "search";
        }

        @Override
        public String description() {
            return "Search locally-cached artifacts by group/artifact substring";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value("<N>", "Cap the number of coordinates displayed (default: no cap).", "--limit"),
                    Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                            .hide());
        }

        @Override
        public List<Param> parameters() {
            return List.of(Param.of(
                    "term", Arity.ONE_OR_MORE, "One or more substrings. All must match (in group or artifact)."));
        }

        @Override
        public int run(Invocation in) {
            List<String> terms = in.positionals();
            Integer limit = in.value("limit").map(Integer::parseInt).orElse(null);
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            JkMavenLocalRepo localRepo = new JkMavenLocalRepo(resolveCacheRoot(cacheDir));
            List<String> lowerTerms =
                    terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            List<JkMavenLocalRepo.Module> hits = localRepo.modules().stream()
                    .filter(m -> allMatch(
                            lowerTerms,
                            m.group().toLowerCase(Locale.ROOT),
                            m.artifact().toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(JkMavenLocalRepo.Module::moduleKey))
                    .toList();
            if (hits.isEmpty()) {
                System.out.println("No cached coordinates match: " + String.join(" ", terms));
                return 1;
            }
            int total = hits.size();
            int shown = limit != null && limit > 0 && total > limit ? limit : total;
            int keyWidth = 0;
            for (int i = 0; i < shown; i++)
                keyWidth = Math.max(keyWidth, hits.get(i).moduleKey().length());
            long versionCount = 0;
            for (int i = 0; i < shown; i++) {
                JkMavenLocalRepo.Module m = hits.get(i);
                List<String> versions = new ArrayList<>(m.versions());
                versions.sort((a, b) -> Versions.compare(b, a));
                versionCount += versions.size();
                String key = m.moduleKey();
                String gap = " ".repeat(Math.max(0, keyWidth - key.length()));
                System.out.println(Coords.module(key)
                        + gap
                        + "  "
                        + String.join(
                                ", ", versions.stream().map(Coords::version).toList()));
            }
            if (shown < total) {
                Theme st = Theme.active();
                System.out.println(
                        Theme.colorize("…", st.darkGray())
                        + " "
                        + Theme.colorize("and ", st.normalGray())
                        + Theme.colorize(String.valueOf(total - shown), st.focused())
                        + " "
                        + Theme.colorize("more", st.normalGray())
                        + " "
                        + Theme.colorize("(pass --limit " + total + " or refine the search)", st.dim()));
            }
            {
                Theme st = Theme.active();
                System.out.println(
                        Theme.colorize(fmtCount(shown), st.focused())
                        + " "
                        + Theme.colorize("coordinate" + (shown == 1 ? "" : "s"), st.settled())
                        + ", "
                        + Theme.colorize(fmtCount(versionCount), st.focused())
                        + " "
                        + Theme.colorize("version" + (versionCount == 1 ? "" : "s") + " cached", st.settled()));
            }
            return 0;
        }

        private static boolean allMatch(List<String> terms, String... fields) {
            for (String t : terms) {
                boolean found = false;
                for (String f : fields) {
                    if (f.contains(t)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            return true;
        }
    }

    public static final class CachePruneCommand implements CliCommand {
        @Override
        public String name() {
            return "prune";
        }

        @Override
        public String description() {
            return "Remove stale action-cache entries and leftover temp files";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                            .hide(),
                    Opt.value(
                            "<days>",
                            "Prune action-cache entries with mtime older than N days. Default: 30.",
                            "--older-than"),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                    Opt.flag("Mark-and-sweep unreferenced CAS objects after expiring stale records.", "--sweep"),
                    Opt.value(
                            "<size>",
                            "Evict oldest CAS objects to <size> (e.g. 20G, 500M). Implies --sweep.",
                            "--max-size"),
                    Opt.flag("Internal: opportunistic prune.", "--background").hide());
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            int olderThanDays = in.value("older-than").map(Integer::parseInt).orElse(30);
            boolean dryRun = in.isSet("dry-run");
            boolean sweep = in.isSet("sweep");
            String maxSize = in.value("max-size").orElse(null);
            boolean background = in.isSet("background");
            GlobalOptions global = GlobalOptions.from(in);

            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                System.out.println("Nothing to prune — " + root + " does not exist.");
                return 0;
            }

            FileChannel lockChan = null;
            FileLock lock = null;
            PrintStream originalOut = null, originalErr = null;
            if (background) {
                Files.createDirectories(root);
                Path lockFile = root.resolve(".prune.lock");
                lockChan = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                lock = lockChan.tryLock();
                if (lock == null) {
                    lockChan.close();
                    return 0;
                }
                Path logFile = root.resolve(".prune-log");
                var logStream = new PrintStream(Files.newOutputStream(
                        logFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
                originalOut = System.out;
                originalErr = System.err;
                System.setOut(logStream);
                System.setErr(logStream);
            }
            try {
                // Mutable result holder closed over by the phase lambda and onSuccess.
                // [0] = totalFiles removed, [1] = totalBytes freed, [2] = evictedReachable count
                long[] result = {0L, 0L, 0L};

                Phase prunePhase = Phase.builder("prune")
                        .scope(1)
                        .execute(ctx -> {
                            ctx.label("Pruning cache…");
                            long cutoffMillis =
                                    System.currentTimeMillis() - (long) olderThanDays * 24L * 60L * 60L * 1000L;
                            long totalFiles = 0;
                            long totalBytes = 0;

                            Path shaDir = root.resolve("sha256");
                            if (Files.isDirectory(shaDir)) {
                                for (Path file : tempFiles(shaDir)) {
                                    long sz = Files.size(file);
                                    if (!dryRun) Files.deleteIfExists(file);
                                    totalFiles++;
                                    totalBytes += sz;
                                }
                            }
                            Path actionsDir = root.resolve("actions");
                            if (Files.isDirectory(actionsDir)) {
                                Path keysDir = actionsDir.resolve("keys");
                                if (Files.isDirectory(keysDir)) {
                                    for (Path file : olderThan(keysDir, cutoffMillis)) {
                                        long sz = Files.size(file);
                                        if (!dryRun) Files.deleteIfExists(file);
                                        totalFiles++;
                                        totalBytes += sz;
                                    }
                                }
                            }
                            var runLogReport =
                                    dev.jkbuild.task.RunLogGc.sweep(root, dev.jkbuild.task.RunLogGc.DEFAULT_TTL, dryRun);
                            totalFiles += runLogReport.deleted();
                            totalBytes += runLogReport.freedBytes();

                            var formatStampReport = dev.jkbuild.task.FormatStampGc.sweep(
                                    root, dev.jkbuild.task.FormatStampGc.DEFAULT_TTL, dryRun);
                            totalFiles += formatStampReport.deleted();
                            totalBytes += formatStampReport.freedBytes();

                            var timingsReport = dev.jkbuild.runtime.PhaseTimings.prune(
                                    root,
                                    dev.jkbuild.runtime.PhaseTimings.Limits.resolve(
                                            dev.jkbuild.util.JkDirs.userConfigFile(), System::getenv),
                                    System.currentTimeMillis(),
                                    dryRun);
                            totalFiles += timingsReport.evictedByAge() + timingsReport.evictedBySize();

                            if (cacheDir == null) {
                                var tmpReport = dev.jkbuild.task.TmpGc.sweep(
                                        dev.jkbuild.util.JkDirs.tmp(), dev.jkbuild.task.TmpGc.DEFAULT_TTL, dryRun);
                                totalFiles += tmpReport.deleted();
                                totalBytes += tmpReport.freedBytes();
                            }

                            boolean doSweep = sweep || maxSize != null;
                            long budgetBytes = maxSize != null ? dev.jkbuild.task.LruEvictor.parseSize(maxSize) : -1L;
                            if (doSweep) {
                                dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(root);
                                Path toolsDir = root.resolve("tools");
                                Path actionsDir2 = root.resolve("actions");
                                var liveRefs = dev.jkbuild.task.CacheRoots.collect(cas, actionsDir2, toolsDir);
                                var sweepReport = dev.jkbuild.task.CasSweep.sweep(cas, liveRefs, dryRun);
                                totalFiles += sweepReport.deleted();
                                totalBytes += sweepReport.freedBytes();
                                if (budgetBytes > 0) {
                                    var ledger = dev.jkbuild.task.AccessLedger.atDefaultPath();
                                    var evictReport = dev.jkbuild.task.LruEvictor.evictDownTo(
                                            cas, budgetBytes, liveRefs, ledger, dryRun);
                                    totalFiles += evictReport.deleted();
                                    totalBytes += evictReport.freedBytes();
                                    result[2] = evictReport.reachableEvicted();
                                    if (!dryRun) {
                                        try {
                                            ledger.compactIfLarge();
                                        } catch (IOException ignored) {
                                        }
                                    }
                                }
                            }

                            result[0] = totalFiles;
                            result[1] = totalBytes;
                            ctx.progress(1);
                        })
                        .build();

                Goal goal = Goal.builder("cache-prune").addPhase(prunePhase).build();

                ConsoleSpec spec = new ConsoleSpec(
                        "Cache",
                        r -> {
                            long files = result[0];
                            long bytes = result[1];
                            if (dryRun) {
                                if (files == 0) return "Dry run: nothing to clean up.";
                                return "Dry run: would remove "
                                        + files + " " + (files == 1 ? "file" : "files")
                                        + ", " + fmtBytes(bytes) + " reclaimable.";
                            }
                            if (files == 0) return "Finished pruning cache. Nothing to clean up.";
                            return "Finished pruning cache. "
                                    + files + " " + (files == 1 ? "file" : "files")
                                    + " removed, " + fmtBytes(bytes) + " reclaimed.";
                        },
                        r -> "Failed to prune cache.",
                        true);

                GoalResult goalResult = GoalConsole.runGoal(
                        goal, GoalConsole.modeFor(global), root, spec, "Cache");

                if (result[2] > 0) {
                    Theme pt = Theme.active();
                    System.err.println(
                            Theme.colorize(Glyphs.BANG, pt.warning())
                            + " "
                            + Theme.colorize(
                                    "evicted "
                                    + result[2]
                                    + " reachable objects to fit the budget — consider raising --max-size.",
                                    pt.settled()));
                }

                return goalResult.success() ? 0 : 1;
            } finally {
                if (background && !dryRun) {
                    try {
                        Files.writeString(
                                root.resolve(dev.jkbuild.task.CachePruneScheduler.LAST_PRUNED_FILE),
                                Long.toString(System.currentTimeMillis()),
                                StandardCharsets.UTF_8);
                    } catch (IOException ignored) {
                    }
                }
                if (originalOut != null) System.setOut(originalOut);
                if (originalErr != null) System.setErr(originalErr);
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (IOException ignored) {
                    }
                }
                if (lockChan != null) {
                    try {
                        lockChan.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private static List<Path> olderThan(Path dir, long cutoffMillis) throws IOException {
            try (var stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile)
                        .filter(p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis() < cutoffMillis;
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .toList();
            }
        }

        private static List<Path> tempFiles(Path dir) throws IOException {
            try (var stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().startsWith(".put-"))
                        .toList();
            }
        }
    }

    public static final class CachePurgeCommand implements CliCommand {
        @Override
        public String name() {
            return "purge";
        }

        @Override
        public String description() {
            return "Delete the entire cache (asks to confirm)";
        }

        @Override
        public List<Opt> options() {
            return List.of(
                    Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir")
                            .hide(),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                    Opt.flag("Skip the confirmation prompt.", "-y", "--yes"));
        }

        @Override
        public int run(Invocation in) throws IOException {
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            boolean assumeYes = in.isSet("yes");
            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) {
                System.out.println(root + " does not exist; nothing to purge.");
                return 0;
            }
            Stats stats = statsOf(root);
            if (dryRun) {
                System.out.printf(
                        "Would remove %s files (%s) from %s%n", fmtCount(stats.files), fmtBytes(stats.bytes), root);
                return 0;
            }
            if (!assumeYes && !confirmPurge(root, stats)) {
                System.out.println("Aborted; nothing removed.");
                return 1;
            }
            deleteContents(root);
            System.out.printf("Removed %s files (%s) from %s%n", fmtCount(stats.files), fmtBytes(stats.bytes), root);
            return 0;
        }

        /** Stern, default-to-no confirmation before wiping the whole cache. */
        private static boolean confirmPurge(Path root, Stats stats) {
            Theme t = Theme.active();
            String bang = Theme.colorize(Glyphs.BANG, t.warning());
            System.out.println();
            System.out.println(
                    bang + " " + Theme.colorize("This permanently deletes the ENTIRE jk cache.", t.errorLabel()));
            System.out.println("  " + root);
            System.out.printf(
                    "  %s files, %s — every cached dependency, CAS blob, and the m2 repo mirror.%n",
                    fmtCount(stats.files), fmtBytes(stats.bytes));
            System.out.println("  jk will re-download everything on the next build.");
            return dev.jkbuild.cli.tui.Confirm.of(bang + " Purge the whole cache?", false)
                    .ask();
        }
    }
}
