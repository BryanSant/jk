// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.ResolveObserver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shrink plugin: R8 {@code --classfile} full mode over a plain Java app + its runtime
 * closure, packaged as one slim executable jar replacing the main artifact. The acceptance is
 * behavioral AND structural: the shrunk jar <em>runs</em> ({@code java -jar} prints the expected
 * output — R8 kept everything reachable), dead library code is gone (an unreferenced
 * commons-lang3 package is absent), and the artifact is a fraction of the input closure.
 *
 * <p>Network test (Maven Central: commons-lang3 + the r8 jar); the CAS persists under build/ so
 * repeat runs are warm.
 */
class ShrinkPluginTest {

    @Test
    void shrunk_jar_runs_and_dead_code_is_gone(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "slim"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [application]
                main = "com.example.slim.Main"

                [shrink]

                [dependencies]
                commons-lang3 = { group = "org.apache.commons", name = "commons-lang3", version = "=3.18.0" }

                # This project runs no tests; owning [test-dependencies] keeps the injected
                # junit-jupiter "latest" out of the graph and the lock deterministic
                # (see KotlinSerializationTest).
                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"   # r8 publishes to Google Maven
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/slim"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example.slim;

                import org.apache.commons.lang3.StringUtils;

                public final class Main {
                    public static void main(String[] args) {
                        System.out.println(StringUtils.capitalize("hello jk"));
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("shrink")).isPresent();

        Goal lock = LockGoals.lockGoal(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        GoalResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

        BuildPipeline.Inputs in = new BuildPipeline.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk.lock"),
                project,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                java.util.Set.of(),
                dev.jkbuild.config.SessionContext.current());
        GoalResult result = BuildPipeline.coreBuilder(in).build().run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        Path jar;
        try (var walk = Files.walk(project.resolve("target"))) {
            jar = walk.filter(p -> p.getFileName().toString().equals("slim-1.0.0.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("slim-1.0.0.jar not produced under target/"));
        }

        // Behavioral: the shrunk jar actually runs — R8 kept the reachable closure.
        Process run = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-jar",
                        jar.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(run.getInputStream().readAllBytes());
        assertThat(run.waitFor()).as("java -jar exit (output: %s)", output).isZero();
        assertThat(output).contains("Hello jk");

        // Structural: dead library code is gone; the app + used helpers survived.
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getJarEntry("com/example/slim/Main.class")).isNotNull();
            assertThat(jf.getJarEntry("org/apache/commons/lang3/StringUtils.class")).isNotNull();
            assertThat(jf.getJarEntry("org/apache/commons/lang3/time/DateUtils.class"))
                    .as("unreferenced commons-lang3 code shrunk away")
                    .isNull();
        }
        // The whole closure (commons-lang3 alone is ~700 KB) collapses to a fraction.
        assertThat(Files.size(jar)).isLessThan(400_000);
    }
}
