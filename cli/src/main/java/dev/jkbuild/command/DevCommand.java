// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.ProjectContext;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.WorkspaceClasspath;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
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

    private static final String DEVTOOLS_MODULE = "org.springframework.boot:spring-boot-devtools";

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

        JkBuild project = JkBuildParser.parse(proj.buildFile());
        BuildLayout layout = BuildLayout.of(projectDir, project);
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        // 1. Initial full build (skip tests — the dev loop optimizes for latency).
        if (!build(projectDir, cache, false)) return 1;

        boolean devtools = devtoolsOnClasspath(projectDir);
        List<Path> watchRoots = watchRoots(projectDir);
        CliOutput.err("jk dev: watching "
                + watchRoots.stream()
                        .map(r -> projectDir.relativize(r).toString())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("src")
                + (devtools ? " — DevTools hot-restart" : " — process restart on change")
                + ". Ctrl-C stops.");

        // 2. Run the app from the classes dir (never a packaged jar: the loop recompiles
        //    into this dir, and DevTools watches it).
        Process app = startApp(projectDir, project, layout, appArgs);

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
                        project = JkBuildParser.parse(proj.buildFile());
                        app = restartApp(app, projectDir, project, layout, appArgs);
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
                    app = restartApp(app, projectDir, project, layout, appArgs);
                }
            }
        } finally {
            if (app.isAlive()) {
                app.destroy();
                if (!app.waitFor(5, TimeUnit.SECONDS)) app.destroyForcibly();
            }
        }
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

    private Process startApp(Path projectDir, JkBuild project, BuildLayout layout, List<String> appArgs)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(JavaHomes.runningJavaHome()
                .resolve("bin")
                .resolve(HostPlatform.isWindows() ? "java.exe" : "java")
                .toString());
        command.add("-cp");
        command.add(joinClasspath(devClasspath(projectDir, project, layout)));
        command.add(mainClass(project, layout));
        command.addAll(appArgs);
        return new ProcessBuilder(command).inheritIO().start();
    }

    private Process restartApp(Process app, Path projectDir, JkBuild project, BuildLayout layout, List<String> appArgs)
            throws IOException, InterruptedException {
        if (app.isAlive()) {
            app.destroy();
            if (!app.waitFor(5, TimeUnit.SECONDS)) app.destroyForcibly();
        }
        CliOutput.err("jk dev: restarting app");
        return startApp(projectDir, project, layout, appArgs);
    }

    /** Classes dir + lockfile RUN classpath (dev-scope deps ride) + workspace siblings. */
    private List<Path> devClasspath(Path projectDir, JkBuild project, BuildLayout layout) throws IOException {
        List<Path> classpath = new ArrayList<>();
        classpath.add(layout.classesDir());
        Path lockFile = projectDir.resolve("jk.lock");
        if (Files.exists(lockFile)) {
            Cas cas = new Cas(cacheDirOverride != null ? cacheDirOverride : JkDirs.cache());
            classpath.addAll(new ClasspathResolver(cas).classpathFor(LockfileReader.read(lockFile), ClasspathResolver.RUN));
        }
        WorkspaceClasspath.Result siblings = WorkspaceClasspath.resolve(projectDir, project, ClasspathResolver.RUN);
        classpath.addAll(siblings.jars());
        return classpath;
    }

    private static String mainClass(JkBuild project, BuildLayout layout) throws IOException {
        if (project.mainClass() != null) return project.mainClass();
        return dev.jkbuild.layout.MainClassScanner.scanUnique(layout.classesDir());
    }

    private static String joinClasspath(List<Path> paths) {
        String sep = System.getProperty("path.separator");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paths.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(paths.get(i).toAbsolutePath());
        }
        return sb.toString();
    }

    private static boolean devtoolsOnClasspath(Path projectDir) {
        Path lockFile = projectDir.resolve("jk.lock");
        if (!Files.exists(lockFile)) return false;
        try {
            Lockfile lock = LockfileReader.read(lockFile);
            for (Lockfile.Artifact a : lock.artifacts()) {
                if (DEVTOOLS_MODULE.equals(a.name())) return true;
            }
        } catch (IOException ignored) {
            /* no lock, no devtools */
        }
        return false;
    }

    // --- filesystem watching --------------------------------------------------

    /** {@code src/} (compact or traditional — both live under it). */
    private static List<Path> watchRoots(Path projectDir) {
        Path src = projectDir.resolve("src");
        return Files.isDirectory(src) ? List.of(src) : List.of();
    }

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
