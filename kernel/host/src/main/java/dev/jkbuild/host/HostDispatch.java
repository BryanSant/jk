// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.host;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.PluginDeclaration;
import dev.jkbuild.run.Goal;
import dev.jkbuild.runtime.BuildPipeline;

import java.nio.file.Files;
import java.util.List;

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

        verifyPlugins(dir, buildFile, lockFile, cache);

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
        registerPlugins(inv, buildFile, builder);
        return builder.build();
    }

    /**
     * For each plugin declared in {@code jk.toml}, verify its JAR is present
     * in the CAS (pinned SHA from {@code jk.lock}). Throws
     * {@link IllegalStateException} with a clear message when any plugin is
     * missing so the Host emits a user-facing error before the build starts.
     *
     * <p>Third-party plugins run with {@code isolation=process} — this method
     * only verifies presence and manifest validity; actual execution is
     * deferred to the phase that invokes the plugin.
     */
    private static void verifyPlugins(java.nio.file.Path dir,
                                      java.nio.file.Path buildFile,
                                      java.nio.file.Path lockFile,
                                      java.nio.file.Path cache) throws Exception {
        List<PluginDeclaration> plugins = JkBuildParser.parse(buildFile).plugins();
        if (plugins.isEmpty()) return;

        if (!Files.exists(lockFile)) {
            throw new IllegalStateException(
                    "jk.lock is missing but plugins are declared — run `jk sync` first");
        }
        var lock = LockfileReader.read(lockFile);
        var cas  = new Cas(cache);

        for (PluginDeclaration pd : plugins) {
            var entry = lock.plugins().stream()
                    .filter(e -> e.coordinate().equals(pd.coordinate()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "plugin '" + pd.coordinate() + "' is declared in jk.toml "
                            + "but not locked — run `jk sync` to pin it"));

            java.nio.file.Path jar = cas.pathFor(entry.sha256Hex());
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException(
                        "plugin '" + pd.coordinate() + "' is locked (sha256:" + entry.sha256Hex()
                        + ") but the JAR is not in the CAS — run `jk sync`");
            }

            // Confirm the JAR contains a valid Plugin service entry.
            var manifest = PluginLoader.readManifest(jar);
            if (manifest == null) {
                throw new IllegalStateException(
                        "plugin '" + pd.coordinate() + "' does not expose a "
                        + "dev.jkbuild.plugin.Plugin service entry");
            }
        }
    }

    /**
     * For each in-process third-party plugin that passed {@link #verifyPlugins},
     * call {@link PluginLoader#register} so the plugin can contribute phases to
     * the goal before it is built.
     *
     * <p>Process-isolated plugins ({@code isolation = "process"}) are skipped here
     * — their phase-contribution protocol is a follow-on item.
     */
    private static void registerPlugins(HostInvocation inv,
                                        java.nio.file.Path buildFile,
                                        dev.jkbuild.run.Goal.Builder goalBuilder) {
        List<PluginDeclaration> decls;
        dev.jkbuild.model.JkBuild project;
        try {
            project = JkBuildParser.parse(buildFile);
            decls   = project.plugins();
        } catch (Exception e) {
            return; // parse error was already caught by verifyPlugins
        }
        if (decls.isEmpty()) return;

        dev.jkbuild.lock.Lockfile lock;
        try {
            lock = LockfileReader.read(inv.lockFile());
        } catch (Exception e) {
            return;
        }
        Cas cas = new Cas(inv.cache());

        for (PluginDeclaration pd : decls) {
            lock.plugins().stream()
                    .filter(e -> e.coordinate().equals(pd.coordinate()))
                    .findFirst()
                    .ifPresent(entry -> {
                        java.nio.file.Path jar = cas.pathFor(entry.sha256Hex());
                        if (!Files.isRegularFile(jar)) return;
                        try {
                            var ctx = new HostPluginContext(project, inv.dir(), goalBuilder);
                            boolean registered = PluginLoader.register(jar, ctx);
                            if (!registered) {
                                // process-isolated: skipped until protocol is implemented
                            }
                        } catch (java.io.IOException e) {
                            // Log but don't fail — verifyPlugins already confirmed jar validity
                            System.err.println("jk-host: plugin registration warning for "
                                    + pd.coordinate() + ": " + e.getMessage());
                        }
                    });
        }
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
