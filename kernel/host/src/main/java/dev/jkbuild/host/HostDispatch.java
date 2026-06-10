// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.run.Goal;
import dev.jkbuild.runtime.BuildPipeline;

import java.nio.file.Files;

/**
 * Maps a {@link HostInvocation#verb()} to the {@link Goal} it should run.
 * Currently delegates to {@link BuildPipeline} for the build-family verbs
 * (build, compile, test); other verbs will be added as they are ported.
 */
public final class HostDispatch {

    private HostDispatch() {}

    /**
     * Build the {@link Goal} for this invocation. Throws
     * {@link IllegalArgumentException} when the verb is unrecognized.
     */
    public static Goal buildGoal(HostInvocation inv) throws Exception {
        return switch (inv.verb()) {
            case "build", "compile", "test", "clean", "image", "native",
                    "publish", "audit", "install", "run" -> buildPipelineGoal(inv);
            default -> throw new IllegalArgumentException(
                    "jk-host: unrecognized verb '" + inv.verb() + "'");
        };
    }

    // --- build-pipeline verbs ----------------------------------------------

    private static Goal buildPipelineGoal(HostInvocation inv) throws Exception {
        var dir       = inv.dir();
        var cache     = inv.cache();
        var buildFile = dir.resolve("jk.toml");
        var lockFile  = inv.lockFile();

        if (!Files.exists(buildFile)) {
            throw new IllegalArgumentException("jk-host: no jk.toml in " + dir);
        }

        boolean testOnly = "compile".equals(inv.verb()) || "test".equals(inv.verb());
        boolean skipTests = inv.skipTests()
                || "compile".equals(inv.verb())
                || "clean".equals(inv.verb());

        int estimatedTestCount = estimateTestCount(dir);

        var inputs = new BuildPipeline.Inputs(
                dir, cache, buildFile, lockFile, lockFile.getParent(),
                inv.workers(), estimatedTestCount, inv.profile(),
                inv.jdksDir(), skipTests, inv.verbose(), testOnly);

        var builder = BuildPipeline.coreBuilder(inputs);
        return builder.build();
    }

    private static int estimateTestCount(java.nio.file.Path dir) {
        // Count @Test-style annotation occurrences across test sources as a
        // denominator estimate for the progress bar. Intentionally approximate.
        int total = 0;
        for (String sub : new String[]{"src/test/java", "src/test/kotlin"}) {
            java.nio.file.Path srcDir = dir.resolve(sub);
            if (!java.nio.file.Files.isDirectory(srcDir)) continue;
            try (var walk = java.nio.file.Files.walk(srcDir)) {
                for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) walk
                        .filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> { String n = p.getFileName().toString(); return n.endsWith(".java") || n.endsWith(".kt"); })::iterator) {
                    String body = java.nio.file.Files.readString(f);
                    // count @Test, @ParameterizedTest, @TestFactory, @TestTemplate, @RepeatedTest
                    var matcher = java.util.regex.Pattern
                            .compile("@(?:Test|ParameterizedTest|TestFactory|TestTemplate|RepeatedTest)\\b")
                            .matcher(body);
                    while (matcher.find()) total++;
                }
            } catch (Exception ignored) {}
        }
        return total == 0 ? 10 : total;
    }
}
