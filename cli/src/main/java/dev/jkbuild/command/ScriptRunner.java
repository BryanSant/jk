// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.engine.EngineClient;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.script.ScriptHeader;
import dev.jkbuild.script.ScriptHeaderParser;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Executes a standalone file as a program — one of:
 *
 * <ul>
 *   <li><b>{@code .java}</b> script (JBang-compatible, PRD §19).
 *   <li><b>{@code .kt}</b> Kotlin script (JBang-compatible, with {@code //KOTLIN <version>}
 *       support).
 *   <li><b>{@code .kts}</b> Kotlin Script — delegated to {@code kotlinc -script}.
 *   <li><b>{@code .jar}</b> already-built artifact.
 * </ul>
 *
 * <p>This is the engine behind {@code jk tool run <file>}. {@code jk run} no longer interprets file
 * arguments — it only runs the current project and forwards every argument to the project's main
 * method. To execute a loose {@code .java}/{@code .kt}/{@code .kts}/{@code .jar} file, reach for
 * {@code jk tool run} instead.
 *
 * <p><b>Engine-hosted</b> (the Stage-5 close of the slim-client inventory's script residue): each
 * mode's <em>preparation</em> — header parse, dependency resolution, compilation, {@code kotlinc}
 * provisioning, jar manifest/embedded-POM inspection — runs inside the resident engine ({@link
 * EngineClient#runScriptPrepare}, built from the same {@code ScriptGoals} the test-only in-process
 * path uses), streaming the standard single-goal progress events. The actual subprocess that
 * exec's the user's program runs <i>after</i> the goal returns — by then the progress widget has
 * wiped itself, so the inferior owns the TTY cleanly; only the header's exec-relevant bits
 * ({@code //JAVA_OPTIONS}) are (re-)parsed client-side.
 */
final class ScriptRunner {

    private final GlobalOptions global;
    private final Path cacheDirOverride;
    private final Path stateDirOverride;
    private final URI repoUrl;
    private final boolean forceRecompile;

    ScriptRunner(
            GlobalOptions global, Path cacheDirOverride, Path stateDirOverride, URI repoUrl, boolean forceRecompile) {
        this.global = global;
        this.cacheDirOverride = cacheDirOverride;
        this.stateDirOverride = stateDirOverride;
        this.repoUrl = repoUrl;
        this.forceRecompile = forceRecompile;
    }

    /**
     * Escape hatch for the fast JVM unit-test suite ONLY — see {@link
     * BuildCommand#engineDisabledForTests()}'s javadoc for the full rationale; a real script run
     * always engine-hosts its preparation.
     */
    private static boolean engineDisabledForTests() {
        return Boolean.getBoolean("jk.test.noEngine")
                || "dev.jkbuild.test.runner.JkRunner".equals(System.getProperty("jk.plugin.class"));
    }

    /**
     * Run {@code file} (dispatched by extension) with {@code args} forwarded to the program. The
     * caller guarantees the target classified as {@link dev.jkbuild.tool.ToolTarget.RunnableFile}.
     */
    int run(Path file, List<String> args) throws IOException, InterruptedException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return runJavaScript(file, args);
        if (name.endsWith(".kts")) return runKtsScript(file, args);
        if (name.endsWith(".kt")) return runKotlinScript(file, args);
        if (name.endsWith(".jar")) return runJar(file, args);
        throw new IllegalArgumentException("unsupported file type: " + file);
    }

    // --- .java -----------------------------------------------------------

    private int runJavaScript(Path script, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            CliOutput.err("jk tool run: script not found: " + script);
            return Exit.NO_INPUT;
        }
        ScriptHeader header = readHeader(script);
        EngineClient.ScriptPrepareOutcome prep = prepare("java", script);
        if (!prep.result().success()) return failureExitCode(prep.result());
        return execJava(prep.classesDir(), prep.classpath(), header.javaOptions(), prep.mainClass(), args);
    }

    // --- .kt -------------------------------------------------------------

    private int runKotlinScript(Path script, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            CliOutput.err("jk tool run: script not found: " + script);
            return Exit.NO_INPUT;
        }
        ScriptHeader header = readHeader(script);
        EngineClient.ScriptPrepareOutcome prep = prepare("kt", script);
        if (!prep.result().success()) return failureExitCode(prep.result());

        // At runtime, the Kotlin stdlib must be on the classpath.
        List<Path> runtime = new ArrayList<>(prep.classpath());
        if (prep.stdlib() != null) runtime.add(prep.stdlib());

        return execJava(prep.classesDir(), runtime, header.javaOptions(), prep.mainClass(), args);
    }

    // --- .kts ------------------------------------------------------------

    private int runKtsScript(Path script, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(script)) {
            CliOutput.err("jk tool run: script not found: " + script);
            return Exit.NO_INPUT;
        }
        EngineClient.ScriptPrepareOutcome prep = prepare("kts", script);
        if (!prep.result().success() || prep.kotlincBin() == null) {
            // "kotlinc-missing" is an EX_SOFTWARE (70) shape; everything
            // else collapses to the generic resolver error code.
            for (GoalResult.Diagnostic d : prep.result().errors()) {
                if ("kotlinc-missing".equals(d.code())) return Exit.SOFTWARE;
            }
            return failureExitCode(prep.result());
        }

        // jk resolved any @file:DependsOn/@file:Repository annotations itself (engine-side);
        // plain kotlinc can't compile them (they're main-kts constructs), so exec a
        // line-preserving copy with those annotations commented out.
        String source = new String(Files.readAllBytes(script), StandardCharsets.UTF_8);
        Path execScript = script.toAbsolutePath();
        String neutralized = dev.jkbuild.script.ScriptHeaderParser.neutralizeKotlinAnnotations(source);
        if (neutralized != null) {
            Path srcDir = stateDir()
                    .resolve("script-cache")
                    .resolve(dev.jkbuild.util.Hashing.sha256Hex(source.getBytes(StandardCharsets.UTF_8)))
                    .resolve("src");
            Files.createDirectories(srcDir);
            execScript = srcDir.resolve(script.getFileName().toString());
            Files.writeString(execScript, neutralized, StandardCharsets.UTF_8);
        }

        List<String> command = new ArrayList<>();
        command.add(prep.kotlincBin().toString());
        command.add("-script");
        // The script's declared deps (@file:DependsOn / //DEPS / //jk dep) — resolved
        // engine-side through jk's CAS, handed to kotlinc instead of main-kts's Ivy.
        if (!prep.classpath().isEmpty()) {
            command.add("-classpath");
            command.add(joinClasspath(prep.classpath()));
        }
        command.add(execScript.toString());
        if (!args.isEmpty()) {
            command.add("--");
            command.addAll(args);
        }
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    // --- .jar ------------------------------------------------------------

    private int runJar(Path jar, List<String> args) throws IOException, InterruptedException {
        if (!Files.isRegularFile(jar)) {
            CliOutput.err("jk tool run: jar not found: " + jar);
            return Exit.NO_INPUT;
        }
        EngineClient.ScriptPrepareOutcome prep = prepare("jar", jar);
        if (!prep.result().success() || prep.mainClass() == null) {
            for (GoalResult.Diagnostic d : prep.result().errors()) {
                if ("no-main-class".equals(d.code())) return Exit.DATA_ERR;
            }
            return failureExitCode(prep.result());
        }

        List<Path> classpath = prep.classpath();
        Path java = JavaHomes.runningJavaHome()
                .resolve("bin")
                .resolve(HostPlatform.isWindows() ? "java.exe" : "java");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        if (classpath.size() == 1) {
            // No extra deps — `java -jar` is cleaner and honors the jar's Class-Path attribute.
            command.add("-jar");
            command.add(jar.toAbsolutePath().toString());
        } else {
            command.add("-cp");
            command.add(joinClasspath(classpath));
            command.add(prep.mainClass());
        }
        command.addAll(args);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    // --- shared helpers --------------------------------------------------

    /**
     * Run one mode's preparation goal — engine-hosted normally, in-process through the {@link
     * dev.jkbuild.cli.engine.InProcessEngine} seam under the test-only bypass — rendering the
     * standard single-goal progress either way.
     */
    private EngineClient.ScriptPrepareOutcome prepare(String mode, Path file)
            throws IOException, InterruptedException {
        GoalConsole.Mode consoleMode = GoalConsole.modeFor(global);
        if (engineDisabledForTests()) {
            return dev.jkbuild.cli.engine.InProcessEngine.require()
                    .scriptPrepare(mode, file.toAbsolutePath(), cacheDir(), stateDir(), repoUrl, forceRecompile,
                            consoleMode);
        }
        return EngineClient.runScriptPrepare(
                dev.jkbuild.engine.EnginePaths.current(),
                new EngineClient.ScriptPrepareRequest(
                        mode, file.toAbsolutePath(), cacheDir(), stateDir(), repoUrl, forceRecompile),
                phases -> GoalConsole.chooseConsoleListener("script", phases, consoleMode));
    }

    /** Client-side header parse — only the exec-relevant bits ({@code //JAVA_OPTIONS}) are read here. */
    private static ScriptHeader readHeader(Path script) throws IOException {
        return ScriptHeaderParser.parse(new String(Files.readAllBytes(script), StandardCharsets.UTF_8));
    }

    /**
     * Map a failed goal to exit code 1. The listener already painted the diagnostic and the "Failed"
     * bar; we don't repeat ourselves.
     */
    private int failureExitCode(GoalResult result) {
        return 1;
    }

    private Path cacheDir() {
        return cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
    }

    private Path stateDir() {
        return stateDirOverride != null ? stateDirOverride : JkDirs.state();
    }

    private int execJava(
            Path classesDir, List<Path> classpath, List<String> jvmArgs, String mainClass, List<String> args)
            throws IOException, InterruptedException {
        Path java = JavaHomes.runningJavaHome()
                .resolve("bin")
                .resolve(HostPlatform.isWindows() ? "java.exe" : "java");
        List<Path> full = new ArrayList<>();
        if (classesDir != null) full.add(classesDir);
        full.addAll(classpath);

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(joinClasspath(full));
        command.add(mainClass);
        command.addAll(args);
        return new ProcessBuilder(command).inheritIO().start().waitFor();
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
}
