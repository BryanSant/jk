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
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

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
@Command(name = "native",
        description = "Build a native binary with GraalVM")
public final class NativeCommand implements Callable<Integer> {

    @Option(names = "--main",
            description = "Main class to compile. Default: read from jk.toml's image.main-class.")
    String mainClass;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Parameters(arity = "0..*", paramLabel = "<native-image-args>",
            description = "Extra arguments forwarded to native-image (after --).")
    List<String> extra = new ArrayList<>();

    @picocli.CommandLine.Mixin dev.jkbuild.cli.BuildOptions buildOpts;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException, InterruptedException {
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
                        .orElse("Built native binary") + " " + BuildCommand.inTime(r),
                r -> "Native build failed " + BuildCommand.inTime(r));
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
