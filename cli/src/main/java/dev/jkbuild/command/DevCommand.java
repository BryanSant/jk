// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.ProjectContext;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code jk dev} — the live dev loop (spring-boot plan §3.7, general-purpose by design): build,
 * run the app from its classes dir with the RUN classpath (dev-scope deps ride), watch the
 * sources, and on change recompile incrementally through the engine.
 *
 * <p>Two restart strategies, picked automatically:
 *
 * <ul>
 *   <li><b>In-place (Boot DevTools):</b> when {@code spring-boot-devtools} is on the RUN
 *       classpath, recompiled classes land in the watched classes dir and DevTools hot-restarts
 *       the context — the JVM never dies.
 *   <li><b>Process restart:</b> otherwise the app process is restarted after each successful
 *       rebuild — the same loop, tool-agnostic.
 * </ul>
 *
 * <p>Resource changes run a full (skip-tests) build so the resource copy happens; {@code jk.toml}
 * changes rebuild and always restart the process (the classpath may have changed shape).
 */
public final class DevCommand implements CliCommand {

    /** Coalesce editor save bursts (rename+write+chmod) into one rebuild. */
    private static final long DEBOUNCE_MILLIS = 150;

    private GlobalOptions global;
    private Path cacheDirOverride;
    private Path jdksDir;

    @Override
    public String name() {
        return "dev";
    }

    @Override
    public String description() {
        return "Run the app and hot-reload on source changes";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                Opt.value("<dir>", "Override the JDK install directory.", "--jdks-dir")
                        .hide());
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.global = GlobalOptions.from(in);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        List<String> appArgs = in.positionals();

        Path projectDir = global.workingDir();
        var proj = ProjectContext.require(projectDir, "dev").orElse(null);
        if (proj == null) return Exit.CONFIG;

        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        // 1. Initial full build (skip tests — the dev loop optimizes for latency).
        if (!build(projectDir, cache, false)) return 1;

        // 2. The engine computes the dev plan: classes-dir + RUN classpath argv, DevTools
        //    detection/injection, watch roots — no jk.toml/jk.lock reasoning client-side.
        dev.jkbuild.engine.protocol.ExecPlan plan = devPlan(projectDir, cache);
        if (plan.error() != null) {
            CliOutput.err("jk dev: " + plan.error());
            return Exit.SOFTWARE;
        }
        // A device artifact (an APK): the loop is rebuild → redeploy via the plugin's declared
        // deploy verb — restart-based, per android-plan §3.4 (Apply-Changes parity is out of scope).
        if (!plan.deployVerb().isEmpty()) {
            return deviceLoop(projectDir, cache, plan, appArgs);
        }
        boolean devtools = plan.hotReload();
        if (plan.devtoolsInjected()) {
            CliOutput.err("jk dev: spring-boot-devtools auto-injected for this session"
                    + " — add it to [dev-dependencies] to make it permanent.");
        }
        List<Path> watchRoots = new ArrayList<>();
        for (String root : plan.watchRoots()) watchRoots.add(Path.of(root));
        CliOutput.err("jk dev: watching "
                + watchRoots.stream()
                        .map(r -> projectDir.relativize(r).toString())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("src")
                + (devtools ? " — DevTools hot-restart" : " — process restart on change")
                + ". Ctrl-C stops.");

        // 3. Run the app from the classes dir (never a packaged jar: the loop recompiles
        //    into this dir, and DevTools watches it).
        Process app = startApp(plan, appArgs);

        // 3. Watch → rebuild → (maybe) restart, until interrupted or the app exits.
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            Map<WatchKey, Path> keys = new HashMap<>();
            for (Path root : watchRoots) registerTree(watcher, keys, root);
            registerDir(watcher, keys, projectDir); // jk.toml lives here

            while (true) {
                WatchKey key = watcher.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) {
                    if (!app.isAlive()) {
                        int exit = app.exitValue();
                        CliOutput.err("jk dev: app exited with code " + exit + " — stopping.");
                        return exit;
                    }
                    continue;
                }

                Changes changes = drainEvents(watcher, keys, key, projectDir);
                if (!changes.any()) continue;

                if (changes.manifest) {
                    CliOutput.err("jk dev: jk.toml changed — full rebuild + restart");
                    if (build(projectDir, cache, false)) {
                        plan = devPlan(projectDir, cache); // classpath shape may have changed
                        if (plan.error() != null) {
                            CliOutput.err("jk dev: " + plan.error());
                            return Exit.SOFTWARE;
                        }
                        devtools = plan.hotReload();
                        app = restartApp(app, plan, appArgs);
                    }
                    continue;
                }
                boolean ok = changes.resources
                        ? build(projectDir, cache, false) // full: resource copy happens in-build
                        : compile(projectDir, cache);
                if (!ok) {
                    CliOutput.err("jk dev: build failed — app keeps running the last good code.");
                    continue;
                }
                if (devtools) {
                    CliOutput.err("jk dev: recompiled — DevTools restarts the context.");
                } else {
                    app = restartApp(app, plan, appArgs);
                }
            }
        } finally {
            if (app.isAlive()) {
                app.destroy();
                if (!app.waitFor(5, TimeUnit.SECONDS)) app.destroyForcibly();
            }
        }
    }

    /**
     * The device dev loop: watch the plan's roots, rebuild on change, and re-dispatch the plugin's
     * deploy verb (install + relaunch happen in the plugin's worker — nothing runs on the host).
     */
    private int deviceLoop(
            Path projectDir, Path cache, dev.jkbuild.engine.protocol.ExecPlan plan, List<String> appArgs)
            throws IOException, InterruptedException {
        List<Path> watchRoots = new ArrayList<>();
        for (String root : plan.watchRoots()) watchRoots.add(Path.of(root));
        CliOutput.err("jk dev: watching "
                + watchRoots.stream()
                        .map(r -> projectDir.relativize(r).toString())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("src")
                + " — redeploy to device on change. Ctrl-C stops.");
        int deployed = deploy(projectDir, cache, plan.deployVerb(), appArgs);
        if (deployed != 0) return deployed;

        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            Map<WatchKey, Path> keys = new HashMap<>();
            for (Path root : watchRoots) registerTree(watcher, keys, root);
            registerDir(watcher, keys, projectDir); // jk.toml lives here
            while (true) {
                WatchKey key = watcher.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) continue;
                Changes changes = drainEvents(watcher, keys, key, projectDir);
                if (!changes.any()) continue;
                if (changes.manifest) CliOutput.err("jk dev: jk.toml changed — full rebuild + redeploy");
                if (!build(projectDir, cache, false)) {
                    CliOutput.err("jk dev: build failed — the device keeps the last good install.");
                    continue;
                }
                int exit = deploy(projectDir, cache, plan.deployVerb(), appArgs);
                if (exit != 0) CliOutput.err("jk dev: deploy failed (exit " + exit + ") — will retry on change.");
            }
        }
    }

    /** Dispatch the deploy verb over the plugin-verb protocol; returns its exit code. */
    private int deploy(Path projectDir, Path cache, String verb, List<String> appArgs) {
        dev.jkbuild.engine.protocol.PluginVerbReport report;
        try {
            report = engineDisabledForTests()
                    ? dev.jkbuild.cli.engine.InProcessEngine.require().pluginVerb(projectDir, cache, verb, appArgs)
                    : dev.jkbuild.cli.engine.EngineClient.pluginVerb(
                            dev.jkbuild.engine.EnginePaths.current(), projectDir, cache, verb, appArgs);
        } catch (Exception e) {
            CliOutput.err("jk dev: " + e.getMessage());
            return Exit.SOFTWARE;
        }
        if (!report.found()) {
            CliOutput.err("jk dev: the packaging plugin declares deploy verb `" + verb + "` but does not"
                    + " register it");
            return Exit.SOFTWARE;
        }
        if (report.error() != null) {
            CliOutput.err("jk dev: " + report.error());
            return 1;
        }
        for (String line : report.output()) CliOutput.out(line);
        return report.exit();
    }

    // --- build/compile through the engine ----------------------------------

    private boolean build(Path projectDir, Path cache, boolean verbose) throws IOException, InterruptedException {
        String target = BuildCommand.buildTarget(projectDir.resolve("jk.toml"), projectDir);
        ConsoleSpec spec = new ConsoleSpec(
                "Dev", r -> Theme.colorize("Built", Theme.active().focused()), r -> "Build failed");
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        GoalResult result;
        if (engineDisabledForTests()) {
            result = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .runBuildGoal(projectDir, cache, jdksDir, true, global.verbose, mode, spec, target)
                    .result();
        } else {
            var session = dev.jkbuild.config.SessionContext.current();
            result = dev.jkbuild.cli.engine.EngineClient.runSingleBuild(
                    dev.jkbuild.engine.EnginePaths.current(),
                    new dev.jkbuild.cli.engine.EngineClient.SingleBuildRequest(
                            projectDir,
                            cache,
                            jdksDir,
                            1,
                            null,
                            true,
                            global.verbose,
                            session.offline(),
                            session.force()),
                    phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, target),
                    new dev.jkbuild.run.TestSummary[1],
                    new String[1]);
        }
        return result.success();
    }

    private boolean compile(Path projectDir, Path cache) throws IOException, InterruptedException {
        String target = BuildCommand.buildTarget(projectDir.resolve("jk.toml"), projectDir);
        ConsoleSpec spec = new ConsoleSpec(
                "Dev", r -> Theme.colorize("Recompiled", Theme.active().focused()), r -> "Compile failed");
        GoalConsole.Mode mode = GoalConsole.modeFor(global);
        GoalResult result;
        if (engineDisabledForTests()) {
            result = dev.jkbuild.cli.engine.InProcessEngine.require()
                    .compileGoal(projectDir, cache, null, global.verbose, mode, spec, target);
        } else {
            var session = dev.jkbuild.config.SessionContext.current();
            result = dev.jkbuild.cli.engine.EngineClient.runCompile(
                    dev.jkbuild.engine.EnginePaths.current(),
                    new dev.jkbuild.cli.engine.EngineClient.CompileRequest(
                            projectDir, cache, null, session.offline(), session.force(), global.verbose),
                    phases -> GoalConsole.chooseConsoleListener(phases, mode, spec, target));
        }
        return result.success();
    }

    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    // --- app process --------------------------------------------------------

    /** Fetch the dev exec plan (engine-hosted; in-process twin under jk.test.noEngine). */
    private dev.jkbuild.engine.protocol.ExecPlan devPlan(Path projectDir, Path cache) throws IOException {
        return engineDisabledForTests()
                ? dev.jkbuild.cli.engine.InProcessEngine.require().execPlan(projectDir, cache, "dev", null, null)
                : dev.jkbuild.cli.engine.EngineClient.execPlan(
                        dev.jkbuild.engine.EnginePaths.current(), projectDir, cache, "dev", null, null);
    }

    private Process startApp(dev.jkbuild.engine.protocol.ExecPlan plan, List<String> appArgs) throws IOException {
        List<String> command = new ArrayList<>(plan.argv());
        command.addAll(appArgs);
        return new ProcessBuilder(command)
                .directory(Path.of(plan.workingDir()).toFile())
                .inheritIO()
                .start();
    }

    private Process restartApp(Process app, dev.jkbuild.engine.protocol.ExecPlan plan, List<String> appArgs)
            throws IOException, InterruptedException {
        if (app.isAlive()) {
            app.destroy();
            if (!app.waitFor(5, TimeUnit.SECONDS)) app.destroyForcibly();
        }
        CliOutput.err("jk dev: restarting app");
        return startApp(plan, appArgs);
    }

    // --- filesystem watching --------------------------------------------------

    private static void registerTree(WatchService watcher, Map<WatchKey, Path> keys, Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                registerDir(watcher, keys, dir);
            }
        }
    }

    private static void registerDir(WatchService watcher, Map<WatchKey, Path> keys, Path dir) throws IOException {
        WatchKey key = dir.register(
                watcher,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        keys.put(key, dir);
    }

    private record Changes(boolean sources, boolean resources, boolean manifest) {

        boolean any() {
            return sources || resources || manifest;
        }
    }

    /**
     * Consume the triggering key plus everything that arrives inside the debounce window, and
     * classify what changed. New directories are registered on the fly (nested package creation).
     */
    private static Changes drainEvents(
            WatchService watcher, Map<WatchKey, Path> keys, WatchKey first, Path projectDir)
            throws IOException, InterruptedException {
        boolean sources = false, resources = false, manifest = false;
        WatchKey key = first;
        long settleUntil = System.currentTimeMillis() + DEBOUNCE_MILLIS;
        while (key != null) {
            Path dir = keys.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                if (!(event.context() instanceof Path rel) || dir == null) continue;
                Path changed = dir.resolve(rel);
                String name = rel.getFileName().toString();
                if (dir.equals(projectDir)) {
                    // Project root: only jk.toml matters (target/, .git/ churn is noise).
                    if (name.equals("jk.toml")) manifest = true;
                    continue;
                }
                if (Files.isDirectory(changed) && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerTree(watcher, keys, changed);
                }
                if (projectDir.relativize(changed).toString().replace('\\', '/').contains("/resources/")) {
                    resources = true;
                } else if (name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts")) {
                    sources = true;
                } else if (!Files.isDirectory(changed)) {
                    resources = true; // non-source file under src/ — treat as a resource
                }
            }
            if (!key.reset()) keys.remove(key);
            long remaining = settleUntil - System.currentTimeMillis();
            key = remaining > 0 ? watcher.poll(remaining, TimeUnit.MILLISECONDS) : watcher.poll();
        }
        return new Changes(sources, resources, manifest);
    }
}
