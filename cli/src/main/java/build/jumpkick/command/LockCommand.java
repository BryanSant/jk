// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import build.jumpkick.cli.CliOutput;
import build.jumpkick.cli.GlobalOptions;
import build.jumpkick.cli.ProjectContext;
import build.jumpkick.cli.engine.EngineClient;
import build.jumpkick.cli.run.ConsoleSpec;
import build.jumpkick.cli.run.PipelineConsole;
import build.jumpkick.cli.theme.Coords;
import build.jumpkick.cli.theme.Theme;
import build.jumpkick.cli.tui.CommandManager;
import build.jumpkick.cli.tui.Glyphs;
import build.jumpkick.http.Http;
import build.jumpkick.library.LibraryCatalog;
import build.jumpkick.model.command.CliCommand;
import build.jumpkick.model.command.Exit;
import build.jumpkick.model.command.Invocation;
import build.jumpkick.model.command.Opt;
import build.jumpkick.repo.LibraryRegistryClient;
import build.jumpkick.run.PipelineListener;
import build.jumpkick.run.PipelineResult;
import build.jumpkick.run.Step;
import build.jumpkick.util.JkDirs;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code jk lock} — resolve declared dependencies and write {@code jk.lock}.
 *
 * <p>Reads repositories + features from the project's jk.toml. Feature selection: {@code
 * --features=A,B} adds those features on top of the declared {@code features.default}; {@code
 * --no-default-features} disables the default list entirely. Cargo semantics.
 *
 * <p>For workspace roots, locking cascades: after the root's own {@code jk.lock} is written, each
 * declared module is locked in declaration order using its own {@code jk.lock} alongside its {@code
 * jk.toml}. {@code workspace:} placeholder deps are resolved to real Maven coords before each
 * module's solve; sibling-internal deps are filtered out (they're injected at build time via {@link
 * build.jumpkick.config.WorkspaceClasspath}).
 *
 * <p><b>Engine-hosted</b> (Wave 1 of the slim-client migration): the PubGrub solve, CAS fetches and
 * git-source materialization run inside the resident engine ({@link EngineClient#runLock}); this
 * command is the renderer — it pre-flights the library-registry refresh, sends the request, and
 * replays the wire events into the exact same live/plain views the in-process path drives. The
 * pipeline machinery itself lives in the engine's {@code LockPipelines} so the test-only in-process path
 * (see {@link #engineDisabledForTests}) runs the identical pipeline.
 */
public final class LockCommand implements CliCommand {

    private List<String> features = List.of();
    private boolean noDefaultFeatures;
    private boolean sources;
    private URI repoUrl;
    private Path cacheDir;
    private GlobalOptions global;

    @Override
    public String name() {
        return "lock";
    }

    @Override
    public String description() {
        return "Resolve versions for dependencies and write jk.lock";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<a,b,...>", "Activate listed features beyond the defaults.", "--features")
                        .splitOn(","),
                Opt.flag("Don't activate the project's default features.", "--no-default-features"),
                Opt.flag("Pin sources JARs for all Maven deps too.", "--sources"),
                Opt.value("<url>", "Override declared repos with a single URL.", "--repo-url")
                        .hide(),
                Opt.value(
                                "<dir>",
                                "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.",
                                "--cache-dir")
                        .hide(),
                Opt.value("<url>", "Override the library registry URL (used by tests).", "--library-registry-url")
                        .hide(),
                Opt.value(
                                "<file>",
                                "Override the downloaded library catalog path (used by tests).",
                                "--library-cache-file")
                        .hide());
    }

    private URI libraryRegistryUrl;
    private Path libraryCacheFile;

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale. Same system property,
     * same "never a user-facing flag" contract; a real {@code jk lock} invocation always engine-hosts.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "build.jumpkick.testrunner.TestRunnerPlugin".equals(System.getProperty("jk.plugin.class"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        this.features = in.values("features");
        this.noDefaultFeatures = in.isSet("no-default-features");
        this.sources = in.isSet("sources");
        this.repoUrl = in.value("repo-url").map(URI::create).orElse(null);
        this.cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        this.libraryRegistryUrl = in.value("library-registry-url").map(URI::create).orElse(null);
        this.libraryCacheFile = in.value("library-cache-file").map(Path::of).orElse(null);
        this.global = GlobalOptions.from(in);

        Path dir = global.workingDir();
        if (ProjectContext.require(dir, "lock").isEmpty()) return Exit.CONFIG;
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();
        Files.createDirectories(cache);

        // Client-side pre-flight: revalidate the downloaded library catalog before anything parses
        // jk.toml — the engine reads the same on-disk cache file, so refreshing it here lands for
        // both the hosted and the in-process path.
        refreshLibraryRegistry(
                global.offline,
                libraryRegistryUrl != null ? libraryRegistryUrl : LibraryRegistryClient.DEFAULT_SOURCE,
                libraryCacheFile != null ? libraryCacheFile : LibraryCatalog.downloadedFile());

        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);
        boolean live = mode == PipelineConsole.Mode.AUTO || mode == PipelineConsole.Mode.QUIET;

        if (engineDisabledForTests()) {
            return build.jumpkick.cli.engine.InProcessEngine.require()
                    .lockInProcess(dir, cache, mode, live, features, noDefaultFeatures, sources, repoUrl);
        }
        // Optimize/start the engine before the Lock pipeline console so a one-time AOT training shows the
        // "Engine — optimizing…" wedge first, then the Lock TUI takes over (never interleaved).
        build.jumpkick.cli.engine.EnginePrewarm.ensure();
        return live ? runHostedLive(dir, cache, mode) : runHostedPlain(dir, cache, mode);
    }

    // ---- engine-hosted paths -------------------------------------------------

    private EngineClient.LockRequest lockRequest(Path dir, Path cache) {
        var session = build.jumpkick.config.SessionContext.current();
        return new EngineClient.LockRequest(
                dir, cache, features, noDefaultFeatures, sources, repoUrl,
                session.offline(), session.force(), global.verbose);
    }

    /**
     * Hosted live path (AUTO / QUIET): one shared {@link CommandManager} spanning root + all
     * workspace modules, driven from wire events — one row per module, per-package completion lines
     * (colorized here, never engine-side), and the final Lock chip.
     */
    private int runHostedLive(Path dir, Path cache, PipelineConsole.Mode mode) {
        boolean animate = mode == PipelineConsole.Mode.AUTO && PipelineConsole.isInteractiveTerminal();
        CommandManager view = CommandManager.pipeline(CliOutput.stdout(), "Lock", animate);
        long start = System.nanoTime();

        AtomicInteger globalLocked = new AtomicInteger(0);
        List<String> errorLines = new ArrayList<>();
        Map<String, String> coordByDir = new java.util.HashMap<>();

        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            @Override
            public PipelineListener onModuleStart(String moduleDir, String coord, List<Step> steps) {
                coordByDir.put(moduleDir, coord);
                // The display label is empty so renderActiveRow produces "module › dep".
                view.addStepLabeled(coord, "lock", "");
                view.stepRunning(coord, "lock");
                // Lock is purely resolution — total is unknown upfront, so we show a
                // static top-line label and record each resolved dep as a completion line.
                view.solveLabel("Locking versions…");
                return new PipelineListener() {};
            }

            @Override
            public void onPackage(String moduleDir, String name, String version) {
                String coord = coordByDir.get(moduleDir);
                // Show active dep in the step row (module › dep via renderActiveRow).
                view.stepMessage(coord, "lock", Coords.module(name, version));
                // Record as a completion line with an absolute count bracket.
                int n = globalLocked.incrementAndGet();
                Theme t = Theme.active();
                String line = Theme.colorize(Glyphs.CHECK, t.success())
                        + " "
                        + ConsoleSpec.countBracket(n, t)
                        + " "
                        + Coords.module(name, version);
                if (view.animating()) {
                    view.addCompletion(line);
                } else {
                    CliOutput.out(line);
                }
            }

            @Override
            public void onModuleFinish(String moduleDir, PipelineResult result, EngineClient.LockCounts counts) {
                view.stepDone(coordByDir.get(moduleDir), "lock", result.success());
                if (!result.success()) {
                    for (PipelineResult.Diagnostic d : result.errors()) {
                        errorLines.add(ConsoleSpec.renderError(d));
                    }
                }
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(build.jumpkick.engine.EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            view.finishPipelineFailure(String.valueOf(e.getMessage()), List.of());
            return Exit.SOFTWARE;
        }
        if (!outcome.success()) {
            errorLines.addAll(outcome.errors());
            view.finishPipelineFailure(lockFailTail(), errorLines);
            return outcome.exitCode();
        }
        view.finishPipelineSuccess(lockSuccessTail(globalLocked.get(), start));
        return 0;
    }

    /** Hosted plain path (--verbose / --output json): one console listener per cascade module. */
    private int runHostedPlain(Path dir, Path cache, PipelineConsole.Mode mode) {
        EngineClient.LockHandler handler = new EngineClient.LockHandler() {
            private PipelineListener current;

            @Override
            public PipelineListener onModuleStart(String moduleDir, String coord, List<Step> steps) {
                current = PipelineConsole.chooseConsoleListener("lock", steps, mode);
                return current;
            }

            @Override
            public void onPackage(String moduleDir, String name, String version) {
                // The engine sends structured lock-package events instead of pre-themed labels;
                // colorize here, client-side, exactly as the in-process pipeline labels itself.
                current.label("resolve-deps", "Resolved " + Coords.module(name, version));
            }
        };

        EngineClient.LockOutcome outcome;
        try {
            outcome = EngineClient.runLock(build.jumpkick.engine.EnginePaths.current(), lockRequest(dir, cache), handler);
        } catch (java.io.IOException e) {
            CliOutput.err("jk lock: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : outcome.errors()) {
            CliOutput.err("jk lock: " + err);
        }
        return outcome.exitCode();
    }

    // ---- shared rendering helpers --------------------------------------------

    /** Failure result tail for the Lock chip (PipelineWedge prepends "Failed to lock"). */
    static String lockFailTail() {
        return "dependencies";
    }

    /** Success chip tail: {@code Lock successful. Resolved N dependencies took T}. */
    static String lockSuccessTail(int pkgs, long startNanos) {
        return Theme.colorize("Lock successful", Theme.active().success())
                + ". Resolved "
                + Theme.colorize(String.valueOf(pkgs), Theme.active().focused())
                + " dependenc" + (pkgs == 1 ? "y" : "ies") + " "
                + ConsoleSpec.took(Duration.ofMillis((System.nanoTime() - startNanos) / 1_000_000));
    }

    /**
     * Best-effort revalidation of the downloaded library catalog layer ({@link
     * LibraryCatalog#downloadedFile()}) before {@code jk.toml} is parsed — parsing is what expands
     * short library names against the catalog, so this needs to land before resolution sees the
     * effective dependency list.
     *
     * <p>Only revalidates a catalog that's already been downloaded; a project that has never run
     * {@code jk library update} keeps resolving against the bundled floor rather than jk silently
     * reaching out to GitHub on its behalf. A conditional GET means the common case (nothing changed
     * upstream) costs one round trip of headers — a 304 — and any failure (offline, unreachable,
     * malformed payload) is swallowed: the existing cache, or the bundled floor if there's none, is
     * good enough to proceed with.
     */
    private static void refreshLibraryRegistry(boolean offline, URI source, Path cacheFile) {
        if (offline) return;
        if (!Files.isRegularFile(cacheFile)) return;
        Path etagFile = LibraryCatalog.etagFileFor(cacheFile);
        try {
            var result = new LibraryRegistryClient(new Http()).fetch(source, etagFile);
            if (result instanceof LibraryRegistryClient.Result.Updated updated) {
                LibraryCatalog.parse(new String(updated.body(), StandardCharsets.UTF_8)); // validate before writing
                writeAtomic(cacheFile, updated.body());
                if (updated.etag() != null) {
                    writeAtomic(etagFile, updated.etag().getBytes(StandardCharsets.UTF_8));
                } else {
                    Files.deleteIfExists(etagFile);
                }
            }
        } catch (Exception ignored) {
            // Fail soft: a stale or bundled catalog is still usable, and `jk lock` shouldn't fail
            // because the library registry is unreachable or handed back something malformed.
        }
    }

    private static void writeAtomic(Path target, byte[] data) throws java.io.IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
