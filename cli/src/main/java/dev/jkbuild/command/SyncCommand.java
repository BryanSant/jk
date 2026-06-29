// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Coords;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceLoader;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.resolver.CacheSync;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalKey;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.run.Phase;
import dev.jkbuild.run.PhaseKind;
import dev.jkbuild.runtime.JdkEnsure;
import dev.jkbuild.runtime.LockFlow;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code jk sync} — bring the local toolchain + dependency cache in line with the project's {@code
 * jk.lock}.
 *
 * <p>Organised as a {@link Goal} with four phases:
 *
 * <ol>
 *   <li>{@code parse-lock} (SYNC) — read {@code jk.lock} or delegate to {@link LockFlow} when it's
 *       missing.
 *   <li>{@code ensure-jdk} (IO, parallel) — resolve or install the project's pinned JDK via {@link
 *       JdkEnsure}.
 *   <li>{@code sync-cas} (IO, parallel) — fetch any locked artifacts the CAS doesn't already hold.
 *       Per-package progress events come from {@link CacheSync.ProgressObserver}.
 *   <li>{@code write-sync-manifest} (SYNC) — stamp {@code actions/synced/<projectFingerprint>}.
 * </ol>
 */
public final class SyncCommand implements CliCommand {

    private Path cacheDir;
    private Path jdksDir;
    private java.net.URI repoUrl;
    private boolean offlinePrepare;
    private boolean sources;
    private GlobalOptions global;

    @Override
    public String name() {
        return "sync";
    }

    @Override
    public String description() {
        return "Ensure our local cache has all project dependencies";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<dir>", "Override the JDK install root. Default: the IntelliJ JDK directory.", "--jdks-dir")
                        .hide(),
                Opt.value("<url>", "Override declared repos with a single URL (for tests).", "--repo-url")
                        .hide(),
                Opt.flag("Prepare for an offline build.", "--offline-prepare"),
                Opt.flag("Also download sources JARs when available.", "--sources"));
    }

    /** Cross-phase keys. Lifted out so each phase reads/writes through the same handle. */
    private static final GoalKey<Lockfile> LOCKFILE = GoalKey.of("lockfile", Lockfile.class);

    private static final GoalKey<JkBuild> BUILD = GoalKey.of("build", JkBuild.class);
    private static final GoalKey<JdkEnsure.Outcome> JDK_OUTCOME = GoalKey.of("jdk-outcome", JdkEnsure.Outcome.class);
    private static final GoalKey<CacheSync.Report> CAS_REPORT = GoalKey.of("cas-report", CacheSync.Report.class);
    private static final GoalKey<dev.jkbuild.runtime.JkWorkerSync.Result> WORKER_REPORT =
            GoalKey.of("worker-report", dev.jkbuild.runtime.JkWorkerSync.Result.class);
    private static final GoalKey<Integer> WORKSPACE_MODULES = GoalKey.of("workspace-modules", Integer.class);
    private static final GoalKey<Boolean> LOCKFILE_CREATED = GoalKey.of("lockfile-created", Boolean.class);

    @Override
    public int run(Invocation in) throws Exception {
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.repoUrl = in.value("repo-url").map(java.net.URI::create).orElse(null);
        this.offlinePrepare = in.isSet("offline-prepare");
        this.sources = in.isSet("sources");
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        Path lockFile = dir.resolve("jk.lock");
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        Phase parseLock = Phase.builder("parse-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("parse jk.lock");
                    if (!Files.exists(lockFile)) {
                        ctx.label("resolve deps");
                        var result = LockFlow.run(dir, cache, List.of(), false, repoUrl);
                        if (result.workspaceModuleCount() > 0) {
                            ctx.put(WORKSPACE_MODULES, result.workspaceModuleCount());
                        }
                        if (result.status() != 0) {
                            ctx.error(
                                    "lock",
                                    result.error() != null
                                            ? result.error()
                                            : "lockfile resolution failed (exit " + result.status() + ")");
                            throw new RuntimeException("lock-flow failed");
                        }
                        ctx.put(LOCKFILE, result.lockfile());
                        if (result.build() != null) ctx.put(BUILD, result.build());
                        ctx.put(LOCKFILE_CREATED, true);
                    } else if (dev.jkbuild.runtime.AutoLock.isStale(dir, lockFile)) {
                        ctx.label("jk.toml changed — updating lock");
                        Lockfile existing = LockfileReader.read(lockFile);
                        Lockfile updated = dev.jkbuild.runtime.AutoLock.maybeReLock(
                                dir,
                                existing,
                                lockFile,
                                cache,
                                repoUrl,
                                dev.jkbuild.util.JkVersion.VERSION,
                                List.of(),
                                true,
                                dev.jkbuild.resolver.ResolveObserver.NOOP);
                        ctx.put(LOCKFILE, updated != null ? updated : existing);
                        var build = parseBuildIfPresent(dir);
                        if (build != null) ctx.put(BUILD, build);
                    } else {
                        ctx.put(LOCKFILE, LockfileReader.read(lockFile));
                        var build = parseBuildIfPresent(dir);
                        if (build != null) ctx.put(BUILD, build);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase ensureJdk = Phase.builder("ensure-jdk")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("resolve JDK");
                    Lockfile lock = ctx.require(LOCKFILE);
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    try {
                        var outcome = JdkEnsure.ensure(dir, jdksDir, build, lock, m -> ctx.warn("jdk", m));
                        ctx.put(JDK_OUTCOME, outcome);
                    } catch (Exception e) {
                        ctx.error("jdk", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Phase syncCas = Phase.builder("sync-cas")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(0) // grown once parse-lock fills lockfile
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    int packages = lock.artifacts().size();
                    if (packages > 0) ctx.updateScope(packages);
                    ctx.label("fetch deps");

                    Cas cas = new Cas(cache);
                    Http http = new Http();
                    // Per-package progress: each callback adds 1 to the
                    // numerator on whatever thread completed the fetch.
                    // The TUI bar advances as individual deps land; the
                    // event log records them ordered by completion.
                    var observer = new CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched " + Coords.module(pkg.name(), pkg.version()));
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void skipped(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String error) {
                            ctx.error("dep", Coords.module(pkg.name(), pkg.version()) + " — " + error);
                            ctx.progress(1);
                        }
                    };
                    boolean refresh = dev.jkbuild.config.ActiveConfig.get().refreshOr(false);
                    var report = new CacheSync(cas, http).sync(lock, observer, refresh);
                    ctx.put(CAS_REPORT, report);
                    if (report.hasErrors()) {
                        throw new RuntimeException("dep fetch had errors");
                    }
                })
                .build();

        // jk's own worker jars (test-runner, kotlin-compiler) — pulled from the
        // local Maven repo into the CAS so `jk test` / Kotlin builds find them by
        // SHA. Best-effort: absent workers warn but don't fail the sync.
        Phase syncWorkers = Phase.builder("sync-workers")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("sync jk workers");
                    Cas cas = new Cas(cache);
                    try {
                        var report = dev.jkbuild.runtime.JkWorkerSync.ensureInCas(
                                cas, new dev.jkbuild.runtime.JkWorkerSync.Observer() {
                                    @Override
                                    public void fetched(String artifact) {
                                        ctx.label("fetched " + artifact);
                                    }

                                    @Override
                                    public void missing(String artifact, String detail) {
                                        // Empty code → ProgressBarListener omits [phase/code] brackets.
                                        ctx.warn("", artifact + " " + detail + ".");
                                    }
                                });
                        ctx.put(WORKER_REPORT, report);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("interrupted syncing jk workers", e);
                    }
                    ctx.progress(1);
                })
                .build();

        Phase writeManifest = Phase.builder("write-sync-manifest")
                .requires("sync-cas")
                .scope(1)
                .execute(ctx -> {
                    ctx.label("stamp reachability manifest");
                    try {
                        Lockfile lock = ctx.require(LOCKFILE);
                        dev.jkbuild.task.SyncManifest.write(cache.resolve("actions"), lockFile, lock);
                    } catch (IOException e) {
                        ctx.warn("manifest", "could not stamp reachability manifest: " + e.getMessage());
                    }
                    ctx.progress(1);
                })
                .build();

        // Sync declared third-party plugin jars from Maven to CAS.
        Phase syncPlugins = Phase.builder("sync-plugins")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(0)
                .execute(ctx -> {
                    Lockfile lock = ctx.require(LOCKFILE);
                    var pluginEntries = lock.plugins();
                    if (pluginEntries.isEmpty()) return;
                    ctx.updateScope(pluginEntries.size());
                    ctx.label("sync plugins");
                    Cas cas = new Cas(cache);
                    JkBuild build = ctx.get(BUILD).orElse(null);
                    dev.jkbuild.repo.RepoGroup repos = build != null
                            ? dev.jkbuild.runtime.RepoGroupBuilder.buildFor(build, repoUrl, cas)
                            : dev.jkbuild.runtime.RepoGroupBuilder.buildFor(
                                    dev.jkbuild.config.JkBuildParser.parse(dir.resolve("jk.toml")), repoUrl, cas);
                    for (var pe : pluginEntries) {
                        ctx.label("sync " + pe.coordinate());
                        String hex = pe.sha256Hex();
                        if (cas.contains(hex)) {
                            ctx.progress(1);
                            continue;
                        }
                        String[] parts = pe.coordinate().split(":", 2);
                        if (parts.length != 2) {
                            ctx.error("plugin", "malformed coordinate: " + pe.coordinate());
                            ctx.progress(1);
                            continue;
                        }
                        var coord = dev.jkbuild.model.Coordinate.of(parts[0], parts[1], pe.version());
                        try {
                            var r = repos.tryFetchArtifact(coord);
                            if (r.isPresent()) {
                                ctx.label("fetched " + pe.coordinate() + ":" + pe.version());
                            } else {
                                ctx.error("plugin", pe.coordinate() + " not found in any repo");
                            }
                        } catch (Exception e) {
                            ctx.error("plugin", pe.coordinate() + " — " + e.getMessage());
                        }
                        ctx.progress(1);
                    }
                })
                .build();

        // Sync sources JARs for packages that have sourcesChecksum pinned in lock.
        Phase syncSources = Phase.builder("sync-sources")
                .kind(PhaseKind.IO)
                .requires("parse-lock")
                .scope(0)
                .execute(ctx -> {
                    if (!sources) return; // opt-in only
                    Lockfile lock = ctx.require(LOCKFILE);
                    long withSrc = lock.artifacts().stream()
                            .filter(p -> p.sourcesChecksum() != null)
                            .count();
                    if (withSrc == 0) return;
                    ctx.updateScope((int) withSrc);
                    ctx.label("sync sources");
                    Cas cas = new Cas(cache);
                    var observer = new dev.jkbuild.resolver.CacheSync.ProgressObserver() {
                        @Override
                        public void fetched(Lockfile.Artifact pkg) {
                            ctx.label("fetched sources " + pkg.name() + ":" + pkg.version());
                            ctx.progress(1);
                        }

                        @Override
                        public void upToDate(Lockfile.Artifact pkg) {
                            ctx.progress(1);
                        }

                        @Override
                        public void failed(Lockfile.Artifact pkg, String error) {
                            ctx.warn("sources", pkg.name() + ":" + pkg.version() + " — " + error);
                            ctx.progress(1);
                        }
                    };
                    try {
                        new dev.jkbuild.resolver.CacheSync(cas, new dev.jkbuild.http.Http())
                                .syncSources(lock, observer);
                    } catch (Exception e) {
                        ctx.warn("sources", "sources sync failed: " + e.getMessage());
                    }
                })
                .build();

        Goal goal = Goal.builder("sync")
                .addPhase(parseLock)
                .addPhase(ensureJdk)
                .addPhase(syncCas)
                .addPhase(syncSources)
                .addPhase(syncPlugins)
                .addPhase(syncWorkers)
                .addPhase(writeManifest)
                .build();

        GoalResult result = GoalConsole.run(goal, GoalConsole.modeFor(global), cache);

        if (result.success()) {
            printSuccessSummary(goal, lockFile);
            // Opportunistic cache prune — no-op when auto-prune is off. Cache
            // settings are user-global only; resolve() reads ~/.jk/config.toml
            // (a project jk.toml's [cache] is intentionally ignored).
            var cacheConfig = dev.jkbuild.config.JkCacheConfig.resolve();
            dev.jkbuild.task.CachePruneScheduler.resolveJkExe()
                    .ifPresent(exe -> dev.jkbuild.task.CachePruneScheduler.maybeRun(cacheConfig, cache, exe));
            // Cascade: sync each module's lock file for workspace roots.
            cascadeSyncModules(dir, cache);
            return 0;
        }
        // The progress-bar listener (or SilentListener on a pipe) has
        // already surfaced the failure — no command-side summary.
        return 1;
    }

    /**
     * For workspace roots: download any artifacts locked by each module's own {@code jk.lock} that
     * aren't already in the CAS. Skips modules whose lock file hasn't been created yet (run {@code jk
     * lock} first).
     */
    private void cascadeSyncModules(Path dir, Path cache) {
        JkBuild root;
        try {
            root = JkBuildParser.parse(dir.resolve("jk.toml"));
        } catch (Exception ignored) {
            return;
        }
        if (!root.isWorkspaceRoot()) return;

        Map<Path, JkBuild> modules;
        try {
            modules = WorkspaceLoader.loadModules(dir, root);
        } catch (Exception e) {
            if (!global.outputIsJson()) {
                System.out.println("jk sync: skipping module sync — " + e.getMessage());
            }
            return;
        }

        Cas cas = new Cas(cache);
        Http http = new Http();
        boolean refresh = dev.jkbuild.config.ActiveConfig.get().refreshOr(false);
        for (Map.Entry<Path, JkBuild> entry : modules.entrySet()) {
            Path moduleDir = entry.getKey();
            Path moduleLock = moduleDir.resolve("jk.lock");
            if (!Files.exists(moduleLock)) {
                if (!global.outputIsJson()) {
                    System.out.println(
                            "jk sync: " + dir.relativize(moduleDir) + "/jk.lock not found — run `jk lock` first");
                }
                continue;
            }
            try {
                Lockfile lock = LockfileReader.read(moduleLock);
                var report = new dev.jkbuild.resolver.CacheSync(cas, http)
                        .sync(lock, dev.jkbuild.resolver.CacheSync.ProgressObserver.NOOP, refresh);
                if (!global.outputIsJson() && (report.fetched() > 0 || report.upToDate() > 0)) {
                    Theme th = Theme.active();
                    System.out.println(
                            Theme.colorize(dir.relativize(moduleDir).toString(), th.path())
                            + ": "
                            + Theme.colorize(String.valueOf(report.fetched()), th.focused())
                            + " "
                            + Theme.colorize("fetched", th.success())
                            + ", "
                            + Theme.colorize(String.valueOf(report.upToDate()), th.focused())
                            + " "
                            + Theme.colorize("up-to-date", th.normalGray())
                            + ", "
                            + Theme.colorize(String.valueOf(report.skipped()), th.focused())
                            + " "
                            + Theme.colorize("skipped", th.normalGray()));
                }
            } catch (Exception e) {
                System.err.println("jk sync: " + dir.relativize(moduleDir) + ": sync failed — " + e.getMessage());
            }
        }
    }

    /**
     * On success, surface the backward-compat one-liners every prior version of {@code jk sync}
     * printed. Tests + dotfiles + scripts grep for these.
     */
    private void printSuccessSummary(Goal goal, Path lockFile) {
        if (global.outputIsJson()) return;
        Theme t = Theme.active();
        goal.get(WORKSPACE_MODULES)
                .ifPresent(n -> System.out.println(
                        Theme.colorize("Workspace:", t.settled())
                        + " "
                        + Theme.colorize(String.valueOf(n), t.focused())
                        + " module"
                        + (n == 1 ? "" : "s")));
        boolean created = goal.get(LOCKFILE_CREATED).orElse(false);
        goal.get(LOCKFILE).ifPresent(lock -> {
            if (created) {
                int n = lock.artifacts().size();
                System.out.println(Theme.colorize("Created", t.settled())
                        + " "
                        + dev.jkbuild.cli.PathDisplay.styledRaw(lockFile)
                        + " ("
                        + Theme.colorize(String.valueOf(n), t.focused())
                        + " package"
                        + (n == 1 ? "" : "s")
                        + ")");
            }
        });
        goal.get(JDK_OUTCOME).ifPresent(SyncCommand::printJdkSummary);
        goal.get(CAS_REPORT)
                .ifPresent(r -> System.out.println(
                        Theme.colorize(String.valueOf(r.fetched()), t.focused())
                        + Theme.colorize(" fetched, ", t.settled())
                        + Theme.colorize(String.valueOf(r.upToDate()), t.focused())
                        + Theme.colorize(" up-to-date, ", t.settled())
                        + Theme.colorize(String.valueOf(r.skipped()), t.focused())
                        + Theme.colorize(" skipped", t.settled())));
        goal.get(WORKER_REPORT).ifPresent(r -> {
            if (r.fetched() > 0 || r.missing() > 0) {
                System.out.println(Theme.colorize("Workers:", t.settled())
                        + " "
                        + Theme.colorize(String.valueOf(r.present()), t.focused())
                        + Theme.colorize(" present, ", t.settled())
                        + Theme.colorize(String.valueOf(r.fetched()), t.focused())
                        + Theme.colorize(" fetched, ", t.settled())
                        + Theme.colorize(String.valueOf(r.missing()), t.focused())
                        + Theme.colorize(" missing", t.settled()));
            }
        });
    }

    private static void printJdkSummary(JdkEnsure.Outcome outcome) {
        if (outcome.jdk().isEmpty()) {
            System.out.println("JDK: (no pin in jk.lock or jk.toml; falling back to JAVA_HOME)");
            return;
        }
        var jdk = outcome.jdk().get();
        switch (outcome.source()) {
            case ALREADY_PINNED, LOCKFILE_INSTALL ->
                System.out.println("JDK: " + jdk.identifier() + " (already installed)");
            case INSTALLED ->
                System.out.println("JDK: installed "
                        + jdk.identifier()
                        + (outcome.specUsed() != null ? " (from spec " + outcome.specUsed() + ")" : ""));
        }
    }

    private static JkBuild parseBuildIfPresent(Path dir) {
        Path buildFile = dir.resolve("jk.toml");
        if (!Files.exists(buildFile)) return null;
        try {
            return JkBuildParser.parse(buildFile);
        } catch (Exception e) {
            return null;
        }
    }
}
