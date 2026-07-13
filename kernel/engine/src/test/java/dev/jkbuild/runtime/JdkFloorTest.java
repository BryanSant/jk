// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.resolver.ResolveObserver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The promised JDK 17 project floor (requirements.md): a {@code jdk = 17}-pinned Kotlin
 * project must build AND run its tests. Two rules under test — workers host on jk's own
 * runtime with the pinned JDK as an input ({@code -jdk-home} for kotlinc, {@code --release}
 * for javac), and everything that rides the user's test JVM (jk-test-runner + the vendored
 * plugin-api SPI/codec) is compiled at {@code --release 17} so the pinned JVM can load it.
 *
 * <p>Network test (Maven Central; provisions temurin-17 on first run); the CAS persists
 * under build/ so repeats are warm.
 */
class JdkFloorTest {

    @Test
    void jdk17_pinned_kotlin_project_builds_and_runs_tests(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "floor17"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 17
                kotlin  = "^2.4.0"
                layout  = "simple"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.1" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Floor.kt"), """
                package com.example.floor
                class Floor {
                    fun jvm(): String = System.getProperty("java.specification.version")
                }
                """);
        Files.createDirectories(project.resolve("test"));
        Files.writeString(project.resolve("test/FloorTest.kt"), """
                package com.example.floor

                import org.junit.jupiter.api.Assertions.assertEquals
                import org.junit.jupiter.api.Test

                class FloorTest {
                    @Test
                    fun tests_run_on_the_pinned_jdk() {
                        // The forked test JVM IS the pinned JDK — that's the point of pinning.
                        assertEquals("17", Floor().jvm())
                    }
                }
                """);

        var parsed = dev.jkbuild.config.JkBuildParser.parse(project.resolve("jk.toml"));
        Goal lock = LockGoals.lockGoal(
                project, parsed, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().errors()).isEmpty();

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
                /* skipTests */ false,
                false);
        Goal goal = BuildPipeline.coreBuilder(in).build();
        GoalResult result = goal.run();
        for (GoalResult.Diagnostic d : result.errors()) {
            System.out.println("DIAG [" + d.phase() + "]: " + d.message());
        }
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).as("jdk=17 pinned Kotlin project builds and its test passes").isTrue();
    }
}
