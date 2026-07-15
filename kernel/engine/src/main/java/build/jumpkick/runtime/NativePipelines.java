// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.config.ImageConfigParser;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.model.JkBuild;
import build.jumpkick.model.command.Exit;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineResult;
import java.nio.file.Path;
import java.util.List;

/**
 * The shared {@code jk native} module pipeline — the full {@link BuildPipelines} plus the {@link
 * BuildPipelines#nativeStep} tail for native-eligible modules — hoisted out of the CLI so the
 * resident engine can host the command (Wave 3 of {@code docs/architecture/slim-client.md}) while the
 * command's test-only in-process path builds the exact same pipeline. The GraalVM home is always
 * resolved <em>client-side</em> (its consent prompt / auto-install owns the terminal) and passed
 * in; the engine only ever runs an already-resolved toolchain.
 */
public final class NativePipelines {

    private NativePipelines() {}

    /**
     * A module is native-eligible only when it sets {@code [native] always = true}
     * (NativeMode.ALWAYS). {@code [native]} absent (DISABLED) or declared without {@code always}
     * (SUPPORTED) are both skipped by {@code jk native}. A main class is <em>not</em> required:
     * with one we build an executable, without one a shared library.
     */
    public static boolean isNativeEligible(JkBuild build) {
        return build.nativeMode() == JkBuild.NativeMode.ALWAYS;
    }

    /**
     * The native-image main class: the CLI {@code --main} override wins, then {@code
     * [native].main-class}, then {@code [image].main}; {@code null} falls through to {@code
     * [application].main} inside {@link BuildPipelines#nativeStep}.
     */
    public static String resolveMain(Path buildFile, String mainOverride) {
        if (mainOverride != null && !mainOverride.isBlank()) return mainOverride;
        try {
            String fromNative = JkBuildParser.parse(buildFile)
                    .nativeConfig()
                    .map(JkBuild.NativeConfig::mainClass)
                    .orElse(null);
            if (fromNative != null) return fromNative;
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
     * Construct (but do not run) one module's pipeline: core steps plus the native-image tail when the
     * module is native-eligible ({@code graalHome} then names the GraalVM the client resolved;
     * ignored otherwise). Non-eligible modules still compile and package so eligible siblings can
     * depend on them.
     */
    public static Pipeline modulePipeline(
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
        BuildPipelines.Inputs inputs = new BuildPipelines.Inputs(
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
                verbose,
                false,
                false,
                java.util.Set.of(),
                build.jumpkick.config.SessionContext.current());
        Pipeline.Builder builder = BuildPipelines.coreBuilder(inputs);
        if (isNativeEligible(module)) {
            builder.addStep(BuildPipelines.nativeStep(
                    moduleDir, cache, lockFile, jdksDir, graalHome, resolveMain(buildFile, mainOverride), extraArgs));
        }
        return builder.build();
    }

    /**
     * {@code jk native}'s exit-code mapping for a failed module pipeline: a native-step "main class"
     * misconfiguration exits {@link Exit#USAGE}, a test failure exits 4, anything else 1.
     */
    public static int failureExitCode(Pipeline pipeline, PipelineResult result) {
        for (PipelineResult.Diagnostic d : result.errors()) {
            if ("native".equals(d.code()) && d.message() != null && d.message().contains("main class")) {
                return Exit.USAGE;
            }
        }
        var testResult = pipeline.get(BuildPipelines.TEST_RESULT).orElse(null);
        if (testResult != null && !testResult.allPassed()) return 4;
        return 1;
    }
}
