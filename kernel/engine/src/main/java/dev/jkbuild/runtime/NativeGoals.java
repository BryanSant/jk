// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared {@code jk native} module goal — the full {@link BuildPipeline} plus the {@link
 * BuildPipeline#nativePhase} tail for native-eligible modules — hoisted out of the CLI so the
 * resident engine can host the verb (Wave 3 of {@code docs/architecture/slim-client.md}) while the
 * command's test-only in-process path builds the exact same goal. The GraalVM home is always
 * resolved <em>client-side</em> (its consent prompt / auto-install owns the terminal) and passed
 * in; the engine only ever runs an already-resolved toolchain.
 */
public final class NativeGoals {

    private NativeGoals() {}

    /**
     * A module is native-eligible only when it sets {@code native = true} (NativeMode.ALWAYS).
     * Absent {@code native} (SUPPORTED) and {@code native = false} (DISABLED) are both skipped by
     * {@code jk native}. A main class is <em>not</em> required: with one we build an executable,
     * without one a shared library.
     */
    public static boolean isNativeEligible(JkBuild build) {
        return build.project().nativeMode() == JkBuild.NativeMode.ALWAYS;
    }

    /**
     * The native-image main class: the CLI {@code --main} override wins, then {@code
     * [native].main-class}, then {@code [image].main}; {@code null} falls through to {@code
     * [project].main} inside {@link BuildPipeline#nativePhase}.
     */
    public static String resolveMain(Path buildFile, String mainOverride) {
        if (mainOverride != null && !mainOverride.isBlank()) return mainOverride;
        try {
            String fromNative = JkBuildParser.parse(buildFile).nativeConfig().mainClass();
            if (fromNative != null && !fromNative.isBlank()) return fromNative;
        } catch (Exception ignored) {
        }
        try {
            String fromImage = ImageConfigParser.parse(buildFile).main();
            if (fromImage != null && !fromImage.isBlank()) return fromImage;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Construct (but do not run) one module's goal: core phases plus the native-image tail when the
     * module is native-eligible ({@code graalHome} then names the GraalVM the client resolved;
     * ignored otherwise). Non-eligible modules still compile and package so eligible siblings can
     * depend on them.
     */
    public static Goal moduleGoal(
            Path moduleDir,
            JkBuild module,
            Path cache,
            Path jdksDir,
            Path graalHome,
            String mainOverride,
            List<String> extraArgs,
            boolean skipTests,
            boolean verbose) {
        Path buildFile = moduleDir.resolve("jk.toml");
        Path lockFile = moduleDir.resolve("jk.lock");
        int estimatedTests = TestSupport.estimateTestCount(moduleDir.resolve("src/test/java"));
        BuildPipeline.Inputs inputs = new BuildPipeline.Inputs(
                moduleDir,
                cache,
                buildFile,
                lockFile,
                moduleDir,
                1,
                estimatedTests,
                null,
                jdksDir,
                skipTests,
                verbose);
        Goal.Builder builder = BuildPipeline.coreBuilder(inputs);
        if (isNativeEligible(module)) {
            builder.addPhase(BuildPipeline.nativePhase(
                    moduleDir, cache, lockFile, jdksDir, graalHome, resolveMain(buildFile, mainOverride), extraArgs));
        }
        return builder.build();
    }

    /**
     * {@code jk native}'s exit-code mapping for a failed module goal: a native-phase "main class"
     * misconfiguration exits {@link Exit#USAGE}, a test failure exits 4, anything else 1.
     */
    public static int failureExitCode(Goal goal, GoalResult result) {
        for (GoalResult.Diagnostic d : result.errors()) {
            if ("native".equals(d.code()) && d.message() != null && d.message().contains("main class")) {
                return Exit.USAGE;
            }
        }
        var testResult = goal.get(BuildPipeline.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }
}
