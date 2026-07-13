// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Workspace;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.Phase;
import dev.jkbuild.task.ActionKey;
import dev.jkbuild.task.CacheGc;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared cache-maintenance goals behind {@code jk cache prune}, {@code jk cache purge}, and
 * {@code jk clean --cache} — hoisted out of the CLI so the resident engine can host them as
 * idle-boundary jobs (Wave 4 of {@code docs/architecture/slim-client.md}: these mutate caches the
 * engine's pipelines read concurrently, so the engine runs them only when no pipeline is in flight)
 * while the commands' test-only in-process path builds the exact same goals.
 */
public final class CacheGoals {

    private CacheGoals() {}

    /** Files removed (or, on a dry run, that would be). {@code gc}: purged CAS blobs. */
    public static final GoalKey<Long> FILES = GoalKey.of("cache-files", Long.class);

    /** Bytes freed (or reclaimable, on a dry run). */
    public static final GoalKey<Long> BYTES = GoalKey.of("cache-bytes", Long.class);

    /** Reachable CAS objects the LRU evictor removed to fit {@code --max-size} (prune only). */
    public static final GoalKey<Long> REACHABLE_EVICTED = GoalKey.of("cache-reachable-evicted", Long.class);

    /** Repo-mirror links removed ({@code gc} only). */
    public static final GoalKey<Long> REPO_LINKS = GoalKey.of("cache-repo-links", Long.class);

    /**
     * Build the prune goal for the cache at {@code root}: expire stale action-cache entries and
     * leftover temp files, GC run logs / format stamps / phase timings, then optionally
     * mark-and-sweep the CAS ({@code sweep}) and LRU-evict down to {@code maxSize}.
     *
     * @param includeJkTmp also sweep {@code ~/.jk/tmp} — only when the default cache dir is in use
     *     (the command passes {@code --cache-dir == null}), matching the historical behavior
     */
    public static Goal pruneGoal(
            Path root, int olderThanDays, boolean dryRun, boolean sweep, String maxSize, boolean includeJkTmp) {
        Phase prunePhase = Phase.builder("prune")
                .scope(1)
                .execute(ctx -> {
                    // Materialized jk versions join the LRU sweep (engine-versioning-plan P6):
                    // keep the running version + anything used inside the retention window;
                    // a pruned pin re-materializes verified on demand.
                    try {
                        var ledger = dev.jkbuild.task.AccessLedger.atDefaultPath();
                        java.util.Map<String, Long> latest = ledger.latestByHash();
                        var prunedVersions = dev.jkbuild.cache.VersionStore.current().prune(
                                dev.jkbuild.util.JkVersion.VERSION,
                                java.time.Duration.ofDays(30),
                                key -> latest.getOrDefault(key, 0L),
                                dev.jkbuild.util.JkDirs.state().resolve("engine"));
                        for (String v : prunedVersions) ctx.warn("prune", "retired unused jk " + v);
                    } catch (java.io.IOException | RuntimeException ignored) {
                        // version sweep is best-effort maintenance
                    }

                    ctx.label("Pruning cache…");
                    long cutoffMillis = System.currentTimeMillis() - (long) olderThanDays * 24L * 60L * 60L * 1000L;
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
                    var runLogReport = dev.jkbuild.task.RunLogGc.sweep(root, dev.jkbuild.task.RunLogGc.DEFAULT_TTL, dryRun);
                    totalFiles += runLogReport.deleted();
                    totalBytes += runLogReport.freedBytes();

                    var formatStampReport =
                            dev.jkbuild.task.FormatStampGc.sweep(root, dev.jkbuild.task.FormatStampGc.DEFAULT_TTL, dryRun);
                    totalFiles += formatStampReport.deleted();
                    totalBytes += formatStampReport.freedBytes();

                    var timingsReport = PhaseTimings.prune(
                            root,
                            PhaseTimings.Limits.resolve(dev.jkbuild.util.JkDirs.userConfigFile(), System::getenv),
                            System.currentTimeMillis(),
                            dryRun);
                    totalFiles += timingsReport.evictedByAge() + timingsReport.evictedBySize();

                    if (includeJkTmp) {
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
                            var evictReport =
                                    dev.jkbuild.task.LruEvictor.evictDownTo(cas, budgetBytes, liveRefs, ledger, dryRun);
                            totalFiles += evictReport.deleted();
                            totalBytes += evictReport.freedBytes();
                            ctx.put(REACHABLE_EVICTED, (long) evictReport.reachableEvicted());
                            if (!dryRun) {
                                try {
                                    ledger.compactIfLarge();
                                } catch (IOException ignored) {
                                }
                            }
                        }
                    }

                    ctx.put(FILES, totalFiles);
                    ctx.put(BYTES, totalBytes);
                    ctx.progress(1);
                })
                .build();
        return Goal.builder("cache-prune").addPhase(prunePhase).build();
    }

    /** Build the purge goal: delete everything under {@code root} (the root dir itself survives). */
    public static Goal purgeGoal(Path root) {
        Phase purgePhase = Phase.builder("purge")
                .execute(ctx -> {
                    ctx.label("Purging cache…");
                    deleteContents(root);
                })
                .build();
        return Goal.builder("cache-purge").addPhase(purgePhase).build();
    }

    /** Build the GC goal ({@code jk clean --cache}): purge CAS blobs idle 90+ days via {@link CacheGc}. */
    public static Goal gcGoal(Path root) {
        Phase gcPhase = Phase.builder("gc")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("Collecting cache…");
                    CacheGc.Report report = CacheGc.run(root, false);
                    ctx.put(FILES, (long) report.purgedBlobs());
                    ctx.put(BYTES, report.freedBytes());
                    ctx.put(REPO_LINKS, (long) report.repoLinksRemoved());
                    ctx.progress(1);
                })
                .build();
        return Goal.builder("cache-gc").addPhase(gcPhase).build();
    }

    /**
     * Build the clear goal for {@code jk cache clear}: invalidate the action-cache entries belonging
     * to the project at {@code projectDir} <em>and its whole workspace</em> (all {@code [workspace]}
     * modules — the cascade), so the next build for any of them can't hit a cache entry.
     *
     * <p>The action cache never stores project identity as such; a cache hit is a direct {@code
     * keys/<actionKey>} lookup, and each record carries a project-qualified {@code TASK
     * <base>@<tag>} line where {@code tag = }{@link ActionKey#taskTag(Path)} of a build-output dir
     * under {@code <module>/target/}. So we identify a project's entries two ways, unioned:
     *
     * <ul>
     *   <li><b>By tag</b> — recompute, via {@link BuildLayout}, the tags for each module's output
     *       dirs (classes, jars, native binaries, OCI tarball, …) and match every {@code
     *       tasks/<base>@<tag>} / {@code keys} / {@code incremental-*} entry with a matching tag,
     *       regardless of the base task name. This is what catches the path-less records — cached
     *       test results and packaged artifacts, whose keys hash content fingerprints, not paths.
     *   <li><b>By input path</b> — a compile record's {@code INPUT} lines record absolute source and
     *       classpath paths; any under a module dir belongs to the project. A safety net for records
     *       (e.g. a custom-named native binary) whose output dir the tag pass didn't enumerate.
     * </ul>
     *
     * We delete the matched {@code keys/<key>} records, their {@code tasks/<taskId>} pointers, and
     * the {@code incremental-java/<taskId>} / {@code incremental-kotlin/<taskId>} state dirs. CAS
     * blobs are left untouched — a later {@code jk cache prune --sweep} reclaims any now-unreferenced
     * ones; removing only the action-cache mapping is what forces the rebuild.
     */
    public static Goal clearGoal(Path cacheRoot, Path projectDir, boolean dryRun) {
        Phase clearPhase = Phase.builder("clear")
                .scope(1)
                .execute(ctx -> {
                    ctx.label(dryRun ? "Inspecting build cache…" : "Clearing build cache…");
                    long[] acc = {0L, 0L}; // {files, bytes}
                    Path actionsDir = cacheRoot.resolve("actions");
                    if (Files.isDirectory(actionsDir)) {
                        List<Path> moduleDirs = resolveModuleDirs(projectDir);
                        Set<String> tags = tagsFor(moduleDirs);
                        List<String> prefixes = moduleDirs.stream()
                                .map(p -> p.toString())
                                .toList();
                        Set<String> deletedTaskIds = new LinkedHashSet<>();

                        // 1) key records: match by qualified-task tag, or by an INPUT path under a module dir.
                        Path keysDir = actionsDir.resolve("keys");
                        if (Files.isDirectory(keysDir)) {
                            try (var stream = Files.list(keysDir)) {
                                for (Path key : (Iterable<Path>) stream::iterator) {
                                    if (!Files.isRegularFile(key)) continue;
                                    String content = Files.readString(key);
                                    String taskId = taskIdOf(content);
                                    boolean hit = (taskId != null && tags.contains(tagOf(taskId)))
                                            || inputsUnder(content, prefixes);
                                    if (!hit) continue;
                                    if (taskId != null) deletedTaskIds.add(taskId);
                                    acc[1] += Files.size(key);
                                    if (!dryRun) Files.deleteIfExists(key);
                                    acc[0]++;
                                }
                            }
                        }

                        // 2) task pointers + incremental state, keyed by the same qualified-task id.
                        deleteQualified(actionsDir.resolve("tasks"), tags, deletedTaskIds, dryRun, acc);
                        deleteQualified(actionsDir.resolve("incremental-java"), tags, deletedTaskIds, dryRun, acc);
                        deleteQualified(actionsDir.resolve("incremental-kotlin"), tags, deletedTaskIds, dryRun, acc);
                    }
                    ctx.put(FILES, acc[0]);
                    ctx.put(BYTES, acc[1]);
                    ctx.progress(1);
                })
                .build();
        return Goal.builder("cache-clear").addPhase(clearPhase).build();
    }

    /** The current project dir plus, if it's in a workspace, every {@code [workspace]} module dir. */
    private static List<Path> resolveModuleDirs(Path projectDir) {
        Path here = projectDir.toAbsolutePath().normalize();
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(here);
        try {
            JkBuild manifest = JkBuildParser.parse(here.resolve("jk.toml"));
            Path wsRoot = manifest.isWorkspaceRoot()
                    ? here
                    : WorkspaceLocator.findRoot(here).orElse(null);
            if (wsRoot != null) {
                dirs.add(wsRoot);
                JkBuild root = wsRoot.equals(here) ? manifest : JkBuildParser.parse(wsRoot.resolve("jk.toml"));
                for (String module : root.workspaceOpt().map(Workspace::modules).orElse(List.of())) {
                    dirs.add(wsRoot.resolve(module).normalize());
                }
            }
        } catch (Exception ignored) {
            // Standalone project, or an unparseable manifest: just this dir.
        }
        return new ArrayList<>(dirs);
    }

    /** Recompute the {@link ActionKey#taskTag} of every build-output dir for each module. */
    private static Set<String> tagsFor(List<Path> moduleDirs) {
        Set<String> tags = new HashSet<>();
        for (Path dir : moduleDirs) {
            JkBuild project;
            try {
                project = JkBuildParser.parse(dir.resolve("jk.toml"));
            } catch (Exception e) {
                continue; // no/invalid manifest here — nothing to tag
            }
            BuildLayout layout = BuildLayout.of(dir, project);
            for (Path out : List.of(
                    layout.classesDir(),
                    layout.testClassesDir(),
                    layout.kotlinClassesDir(),
                    layout.kotlinTestClassesDir(),
                    layout.mainJar(),
                    layout.shadowJar(),
                    layout.sourcesJar(),
                    layout.javadocJar(),
                    layout.nativeBinary(),
                    layout.nativeLibrary(),
                    layout.ociImageTar())) {
                tags.add(ActionKey.taskTag(out));
            }
        }
        return tags;
    }

    /** First {@code TASK <id>} line of an action record, or {@code null}. */
    private static String taskIdOf(String recordContent) {
        for (String line : recordContent.split("\n", -1)) {
            if (line.startsWith("TASK ")) return line.substring("TASK ".length()).trim();
        }
        return null;
    }

    /** The {@code @<tag>} suffix of a qualified task id, or {@code ""} for an unqualified name. */
    private static String tagOf(String taskId) {
        int at = taskId.lastIndexOf('@');
        return at < 0 ? "" : taskId.substring(at + 1);
    }

    /** True when any {@code INPUT} source/classpath path in the record is under one of {@code prefixes}. */
    private static boolean inputsUnder(String recordContent, List<String> prefixes) {
        for (String line : recordContent.split("\n", -1)) {
            if (!line.startsWith("INPUT ")) continue;
            String body = line.substring("INPUT ".length());
            int sp = body.indexOf(' ');
            if (sp < 0) continue;
            String pathKey = body.substring(sp + 1);
            if (pathKey.startsWith("cp:")) pathKey = pathKey.substring(3);
            else if (pathKey.startsWith("pp:")) pathKey = pathKey.substring(3);
            for (String prefix : prefixes) {
                if (pathKey.equals(prefix) || pathKey.startsWith(prefix + "/")) return true;
            }
        }
        return false;
    }

    /**
     * Delete every child of {@code dir} whose name is a qualified task id with a tag in {@code tags},
     * or whose name is in {@code alsoDelete}. Handles both plain files (task pointers) and directory
     * trees (incremental state), accumulating {@code {files, bytes}} into {@code acc}.
     */
    private static void deleteQualified(
            Path dir, Set<String> tags, Set<String> alsoDelete, boolean dryRun, long[] acc) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                if (!tags.contains(tagOf(name)) && !alsoDelete.contains(name)) continue;
                if (Files.isDirectory(child)) {
                    try (var tree = Files.walk(child)) {
                        List<Path> paths = tree.sorted(Comparator.reverseOrder()).toList();
                        for (Path p : paths) {
                            if (Files.isRegularFile(p)) {
                                acc[1] += Files.size(p);
                                acc[0]++;
                            }
                            if (!dryRun) Files.deleteIfExists(p);
                        }
                    }
                } else {
                    acc[1] += Files.size(child);
                    if (!dryRun) Files.deleteIfExists(child);
                    acc[0]++;
                }
            }
        }
    }

    /** Recursively delete everything under {@code root}, keeping {@code root} itself. */
    public static void deleteContents(Path root) throws IOException {
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
