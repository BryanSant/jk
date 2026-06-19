// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.repo.JkMavenLocalRepo;
import dev.jkbuild.resolver.Versions;
import dev.jkbuild.util.JkDirs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * {@code jk cache} — manage the on-disk cache at {@code $JK_CACHE_DIR}.
 */
public final class CacheCommand implements CliCommand {

    @Override public String name() { return "cache"; }
    @Override public String description() { return "Manage the jk download / action cache"; }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new CacheDirCommand(), new CacheInfoCommand(), new CacheSearchCommand(), new CachePruneCommand(), new CachePurgeCommand());
    }

    @Override
    public int run(Invocation in) { return 64; }

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
                files++; bytes += Files.size(p);
            }
        }
        return new Stats(files, bytes);
    }

    static void deleteContents(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(root))
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }

    static String fmtCount(long n) { return String.format("%,d", n); }

    static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes; int unit = -1;
        do { v /= 1024.0; unit++; } while (v >= 1024.0 && unit < units.length - 1);
        return String.format("%.1f %s", v, units[unit]);
    }

    // --- subcommands defined here to access private helpers ----------------------

    public static final class CacheDirCommand implements CliCommand {
        @Override public String name() { return "dir"; }
        @Override public String description() { return "Print the cache directory path"; }
        @Override public List<Opt> options() {
            return List.of(Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide());
        }
        @Override public int run(Invocation in) {
            System.out.println(resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null)));
            return 0;
        }
    }

    public static final class CacheInfoCommand implements CliCommand {
        @Override public String name() { return "info"; }
        @Override public String description() { return "Show cache size and contents"; }
        @Override public List<Opt> options() {
            return List.of(Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide());
        }
        @Override public int run(Invocation in) throws IOException {
            Path root = resolveCacheRoot(in.value("cache-dir").map(Path::of).orElse(null));
            System.out.println("Cache directory: " + dev.jkbuild.cli.PathDisplay.styledRaw(root));
            if (!Files.isDirectory(root)) { System.out.println("  (not yet created)"); return 0; }
            Stats sha = statsOf(root.resolve("sha256"));
            Stats actions = statsOf(root.resolve("actions"));
            System.out.println();
            System.out.printf("  CAS blobs:     %s files, %s%n", fmtCount(sha.files), fmtBytes(sha.bytes));
            System.out.printf("  Action cache:  %s files, %s%n", fmtCount(actions.files), fmtBytes(actions.bytes));
            System.out.printf("  Total:         %s files, %s%n", fmtCount(sha.files + actions.files), fmtBytes(sha.bytes + actions.bytes));
            return 0;
        }
    }

    public static final class CacheSearchCommand implements CliCommand {
        @Override public String name() { return "search"; }
        @Override public String description() { return "Search locally-cached artifacts by group/artifact substring"; }
        @Override public List<Opt> options() {
            return List.of(
                    Opt.value("<N>", "Cap the number of coordinates displayed (default: no cap).", "--limit"),
                    Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide());
        }
        @Override public List<Param> parameters() {
            return List.of(Param.of("term", Arity.ONE_OR_MORE, "One or more substrings. All must match (in group or artifact)."));
        }
        @Override public int run(Invocation in) {
            List<String> terms = in.positionals();
            Integer limit = in.value("limit").map(Integer::parseInt).orElse(null);
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            JkMavenLocalRepo localRepo = new JkMavenLocalRepo(resolveCacheRoot(cacheDir));
            List<String> lowerTerms = terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            List<JkMavenLocalRepo.Module> hits = localRepo.modules().stream()
                    .filter(m -> allMatch(lowerTerms, m.group().toLowerCase(Locale.ROOT), m.artifact().toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(JkMavenLocalRepo.Module::moduleKey)).toList();
            if (hits.isEmpty()) { System.out.println("No cached coordinates match: " + String.join(" ", terms)); return 1; }
            int total = hits.size();
            int shown = limit != null && limit > 0 && total > limit ? limit : total;
            int keyWidth = 0;
            for (int i = 0; i < shown; i++) keyWidth = Math.max(keyWidth, hits.get(i).moduleKey().length());
            long versionCount = 0;
            for (int i = 0; i < shown; i++) {
                JkMavenLocalRepo.Module m = hits.get(i);
                List<String> versions = new ArrayList<>(m.versions());
                versions.sort((a, b) -> Versions.compare(b, a));
                versionCount += versions.size();
                String key = m.moduleKey();
                String gap = " ".repeat(Math.max(0, keyWidth - key.length()));
                System.out.println(Coords.module(key) + gap + "  " + String.join(", ", versions.stream().map(Coords::version).toList()));
            }
            if (shown < total) System.out.println("… and " + (total - shown) + " more (pass --limit " + total + " or refine the search)");
            System.out.printf("%s coordinate%s, %s version%s cached%n", fmtCount(shown), shown == 1 ? "" : "s", fmtCount(versionCount), versionCount == 1 ? "" : "s");
            return 0;
        }
        private static boolean allMatch(List<String> terms, String... fields) {
            for (String t : terms) {
                boolean found = false;
                for (String f : fields) { if (f.contains(t)) { found = true; break; } }
                if (!found) return false;
            }
            return true;
        }
    }

    public static final class CachePruneCommand implements CliCommand {
        @Override public String name() { return "prune"; }
        @Override public String description() { return "Remove stale action-cache entries and leftover temp files"; }
        @Override public List<Opt> options() {
            return List.of(
                    Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                    Opt.value("<days>", "Prune action-cache entries with mtime older than N days. Default: 30.", "--older-than"),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                    Opt.flag("After expiring stale records, mark-and-sweep unreferenced objects out of the CAS pool.", "--sweep"),
                    Opt.value("<size>", "After sweep, evict oldest-accessed objects until the CAS pool is under <size> (e.g. 20G, 500M). Forces --sweep.", "--max-size"),
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

            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) { System.out.println("Nothing to prune — " + root + " does not exist."); return 0; }

            java.nio.channels.FileChannel lockChan = null;
            java.nio.channels.FileLock lock = null;
            java.io.PrintStream originalOut = null, originalErr = null;
            if (background) {
                Files.createDirectories(root);
                Path lockFile = root.resolve(".prune.lock");
                lockChan = java.nio.channels.FileChannel.open(lockFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
                lock = lockChan.tryLock();
                if (lock == null) { lockChan.close(); return 0; }
                Path logFile = root.resolve(".prune-log");
                var logStream = new java.io.PrintStream(java.nio.file.Files.newOutputStream(logFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));
                originalOut = System.out; originalErr = System.err;
                System.setOut(logStream); System.setErr(logStream);
            }
            try {
                long cutoffMillis = System.currentTimeMillis() - (long) olderThanDays * 24L * 60L * 60L * 1000L;
                int recordsExpired = 0; long recordsBytes = 0; int tempsCleared = 0; long tempsBytes = 0;
                Path shaDir = root.resolve("sha256");
                if (Files.isDirectory(shaDir)) { for (Path file : tempFiles(shaDir)) { long sz = Files.size(file); if (!dryRun) Files.deleteIfExists(file); tempsCleared++; tempsBytes += sz; } }
                Path actionsDir = root.resolve("actions");
                if (Files.isDirectory(actionsDir)) { for (Path file : olderThan(actionsDir, cutoffMillis)) { long sz = Files.size(file); if (!dryRun) Files.deleteIfExists(file); recordsExpired++; recordsBytes += sz; } }
                var runLogReport = dev.jkbuild.task.RunLogGc.sweep(root, dev.jkbuild.task.RunLogGc.DEFAULT_TTL, dryRun);
                var tmpReport = cacheDir == null ? dev.jkbuild.task.TmpGc.sweep(dev.jkbuild.util.JkDirs.tmp(), dev.jkbuild.task.TmpGc.DEFAULT_TTL, dryRun) : new dev.jkbuild.task.TmpGc.Report(0, 0L);
                boolean doSweep = sweep || maxSize != null;
                long budgetBytes = maxSize != null ? dev.jkbuild.task.LruEvictor.parseSize(maxSize) : -1L;
                int sweptCount = 0; long sweptBytes = 0; int sweptKept = 0; int evictedCount = 0; long evictedBytes = 0; int evictedReachable = 0; long finalSize = -1L;
                if (doSweep) {
                    dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(root);
                    Path toolsDir = root.resolve("tools");
                    var liveRefs = dev.jkbuild.task.CacheRoots.collect(cas, actionsDir, toolsDir);
                    var sweepReport = dev.jkbuild.task.CasSweep.sweep(cas, liveRefs, dryRun);
                    sweptCount = sweepReport.deleted(); sweptBytes = sweepReport.freedBytes(); sweptKept = sweepReport.kept();
                    if (budgetBytes > 0) {
                        var ledger = dev.jkbuild.task.AccessLedger.atDefaultPath();
                        var evictReport = dev.jkbuild.task.LruEvictor.evictDownTo(cas, budgetBytes, liveRefs, ledger, dryRun);
                        evictedCount = evictReport.deleted(); evictedBytes = evictReport.freedBytes(); evictedReachable = evictReport.reachableEvicted(); finalSize = evictReport.finalSize();
                        if (!dryRun) { try { ledger.compactIfLarge(); } catch (IOException ignored) {} }
                    }
                }
                String verb = dryRun ? "Would prune" : "Pruned";
                System.out.printf("%s: records expired %s (%s), temps %s (%s), run-logs %s (%s)", verb, fmtCount(recordsExpired), fmtBytes(recordsBytes), fmtCount(tempsCleared), fmtBytes(tempsBytes), fmtCount(runLogReport.deleted()), fmtBytes(runLogReport.freedBytes()));
                if (cacheDir == null) System.out.printf(", tmp %s (%s)", fmtCount(tmpReport.deleted()), fmtBytes(tmpReport.freedBytes()));
                if (doSweep) System.out.printf(", swept %s (%s); kept %s", fmtCount(sweptCount), fmtBytes(sweptBytes), fmtCount(sweptKept));
                if (budgetBytes > 0) System.out.printf("; evicted %s (%s) to fit %s", fmtCount(evictedCount), fmtBytes(evictedBytes), fmtBytes(budgetBytes));
                System.out.println();
                if (evictedReachable > 0) System.err.println("Warning: evicted " + evictedReachable + " reachable objects to fit the budget — consider raising --max-size.");
                return 0;
            } finally {
                if (background && !dryRun) { try { Files.writeString(root.resolve(dev.jkbuild.task.CachePruneScheduler.LAST_PRUNED_FILE), Long.toString(System.currentTimeMillis()), java.nio.charset.StandardCharsets.UTF_8); } catch (IOException ignored) {} }
                if (originalOut != null) System.setOut(originalOut);
                if (originalErr != null) System.setErr(originalErr);
                if (lock != null) { try { lock.release(); } catch (IOException ignored) {} }
                if (lockChan != null) { try { lockChan.close(); } catch (IOException ignored) {} }
            }
        }

        private static List<Path> olderThan(Path dir, long cutoffMillis) throws IOException {
            try (var stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile).filter(p -> { try { return Files.getLastModifiedTime(p).toMillis() < cutoffMillis; } catch (IOException e) { return false; } }).toList();
            }
        }
        private static List<Path> tempFiles(Path dir) throws IOException {
            try (var stream = Files.walk(dir)) {
                return stream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith(".put-")).toList();
            }
        }
    }

    public static final class CachePurgeCommand implements CliCommand {
        @Override public String name() { return "purge"; }
        @Override public String description() { return "Delete the entire cache (asks to confirm)"; }
        @Override public List<Opt> options() {
            return List.of(
                    Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                    Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                    Opt.flag("Skip the confirmation prompt.", "-y", "--yes"));
        }
        @Override public int run(Invocation in) throws IOException {
            Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
            boolean dryRun = in.isSet("dry-run");
            boolean assumeYes = in.isSet("yes");
            Path root = resolveCacheRoot(cacheDir);
            if (!Files.isDirectory(root)) { System.out.println(root + " does not exist; nothing to purge."); return 0; }
            Stats stats = statsOf(root);
            if (dryRun) {
                System.out.printf("Would remove %s files (%s) from %s%n", fmtCount(stats.files), fmtBytes(stats.bytes), root);
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
            String bang = Theme.colorize("‼", t.error());
            System.out.println();
            System.out.println(bang + " " + Theme.colorize("This permanently deletes the ENTIRE jk cache.", t.errorLabel()));
            System.out.println("  " + root);
            System.out.printf("  %s files, %s — every cached dependency, CAS blob, and the m2 repo mirror.%n",
                    fmtCount(stats.files), fmtBytes(stats.bytes));
            System.out.println("  jk will re-download everything on the next build.");
            return dev.jkbuild.cli.tui.Confirm.of(bang + " Purge the whole cache?", false).ask();
        }
    }
}
