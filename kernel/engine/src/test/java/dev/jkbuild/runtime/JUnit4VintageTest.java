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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan §3.6's JUnit-4 path: Android's default test style is JUnit 4, and jk's runner
 * discovers engines from the <em>test classpath</em> (ServiceLoader over junit-platform-engine) —
 * so declaring {@code junit:junit} + {@code org.junit.vintage:junit-vintage-engine} in
 * {@code [test-dependencies]} runs {@code @org.junit.Test} classes with zero runner changes.
 * This proves that contract on a plain JVM module (the Android flavor differs only in classpath
 * additions — platform stubs + Robolectric config — wired by the android plugin).
 */
class JUnit4VintageTest {

    @Test
    void junit4_tests_run_through_the_vintage_engine(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("j4"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "j4"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [test-dependencies]
                junit          = { group = "junit", name = "junit", version = "=4.13.2" }
                vintage-engine = { group = "org.junit.vintage", name = "junit-vintage-engine", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example"));
        Files.writeString(src.resolve("Adder.java"), """
                package com.example;

                public final class Adder {
                    public static int add(int a, int b) { return a + b; }
                }
                """);
        Path test = Files.createDirectories(project.resolve("test/com/example"));
        Files.writeString(test.resolve("AdderTest.java"), """
                package com.example;

                import static org.junit.Assert.assertEquals;
                import org.junit.Test;

                public class AdderTest {
                    @Test
                    public void adds() {
                        assertEquals(4, Adder.add(2, 2));
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Goal lock = LockGoals.lockGoal(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().success()).isTrue();

        BuildPipeline.Inputs in = new BuildPipeline.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk.lock"),
                project,
                1,
                1,
                null,
                null,
                false, // run the tests — that IS the assertion
                false);
        GoalResult result = BuildPipeline.coreBuilder(in).build().run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).as("JUnit4 test discovered and passed via vintage").isTrue();
    }
}
