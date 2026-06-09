// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.runtime.BuildPipeline;

import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code jk native} — build a GraalVM-compiled native binary for the project,
 * from source. Runs the full {@linkplain BuildPipeline build pipeline}
 * (compile → test → package) and then composes the native-image tail onto the
 * <em>same</em> goal, so there is no "run {@code jk build} first" step and no
 * nested {@code jk} process — native-image runs as a direct subprocess.
 *
 * <p>The GraalVM JDK is the project's pinned JDK (else the running JVM);
 * classpath is the freshly-built main jar plus {@code jk.lock}'s runtime deps.
 */
public final class NativeCommand implements CliCommand {

    @Override public String name() { return "native"; }
    @Override public String description() { return "Build a native binary with GraalVM"; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<class>", "Main class to compile. Default: read from jk.toml's image.main-class.", "--main"),
                Opt.value("<dir>", "Override the jk cache directory.", "--cache-dir").hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide(),
                Opt.flag("Skip compiling and running tests.", "--skip-tests"),
                Opt.flag("Run the build in-process instead of via the Workspace Host JVM.", "--no-host").hide());
    }
    @Override public List<dev.jkbuild.model.command.Param> parameters() {
        return List.of(dev.jkbuild.model.command.Param.of("native-image-args", dev.jkbuild.model.command.Arity.ZERO_OR_MORE, "Extra arguments forwarded to native-image (after --)"));
    }

    String mainClass;
    Path cacheDirOverride;
    Path jdksDir;
    List<String> extra = new ArrayList<>();
    dev.jkbuild.cli.BuildOptions buildOpts;
    GlobalOptions global;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.extra = in.positionals();
        this.buildOpts = new dev.jkbuild.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        boolean useHost = !in.isSet("no-host");
        Path projectDir = global.workingDir();
        Path buildFile = projectDir.resolve("jk.toml");
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        if (!Files.exists(buildFile)) {
            System.err.println("jk native: " + buildFile + " not found.");
            return 66;
        }

        // Resolve the main class up front: --main wins, then [image] main-class;
        // a null here lets the native phase fall back to [project] main.
        String resolvedMain = mainClass;
        if (resolvedMain == null || resolvedMain.isBlank()) {
            try {
                resolvedMain = ImageConfigParser.parse(buildFile).mainClass();
            } catch (RuntimeException ignored) { /* fall through to [project] main */ }
        }

        Path lockFile = projectDir.resolve("jk.lock");

        if (useHost) {
            dev.jkbuild.host.HostInvocation inv = new dev.jkbuild.host.HostInvocation(
                    "native", projectDir, cache, lockFile, jdksDir, null, 1,
                    buildOpts.skipTests, global.verbose, global.outputIsJson());
            var consoleSpec = new ConsoleSpec("Native Build",
                    r -> "Built native binary", r -> "Native build failed");
            int code = dev.jkbuild.cli.run.HostLauncher.tryRun(
                    inv, GoalConsole.modeFor(global), consoleSpec, global.verbose);
            if (code >= 0) return code;
        }

        int estimatedTestCount = TestCommand.estimateTestCount(projectDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                projectDir, cache, buildFile, lockFile, projectDir,
                1, estimatedTestCount, null, jdksDir, buildOpts.skipTests, global.verbose);

        // Core build (jar from source) + the forced native tail in one goal.
        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        builder.addPhase(BuildPipeline.nativePhase(
                projectDir, cache, lockFile, jdksDir, resolvedMain, extra));
        Goal goal = builder.build();

        ConsoleSpec spec = new ConsoleSpec("Native Build",
                r -> goal.get(BuildPipeline.LAYOUT)
                        .map(l -> "Built native binary " + l.nativeBinary().getFileName())
                        .orElse("Built native binary"),
                r -> "Native build failed");
        GoalResult result = GoalConsole.runGoal(goal, GoalConsole.modeFor(global), cache, spec,
                BuildCommand.buildTarget(buildFile, projectDir));

        if (result.success()) return 0;
        // Translate diagnostics into exit codes: 64 (EX_USAGE) for a missing
        // main class, 4 for test failures, 1 otherwise.
        for (GoalResult.Diagnostic d : result.errors()) {
            if ("native".equals(d.code()) && d.message().contains("main class")) return 64;
        }
        var testResult = goal.get(BuildPipeline.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }
}
