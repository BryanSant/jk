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
 * The FIRST build against a never-installed pinned JDK must already run on that JDK:
 * {@code JAVA_HOME} is published by ensure-jdk from its own install outcome, not
 * snapshotted in parse-build before the install exists (the old order compiled/tested the
 * first build on the running JVM and self-healed on the second — wrong once is wrong).
 *
 * <p>An empty {@code jdksDir} override reproduces the first-run shape deterministically.
 * Network test (Maven Central + the JDK feed; the CAS under build/ keeps repeats warm).
 */
class FirstBuildJdkTest {

    @Test
    void first_build_with_uninstalled_pin_tests_on_the_pinned_jdk(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path freshJdks = Files.createDirectories(tmp.resolve("jdks"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "first17"
                group   = "com.example"
                version = "1.0.0"
                jdk     = 17
                layout  = "simple"

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "=6.1.1" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/F.java"), """
                class F {}
                """);
        Files.createDirectories(project.resolve("test"));
        Files.writeString(project.resolve("test/FTest.java"), """
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;

                class FTest {
                    @Test
                    void first_build_already_runs_tests_on_the_pinned_jdk() {
                        assertEquals("17", System.getProperty("java.specification.version"));
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
                freshJdks, // EMPTY: the pin is not installed here — the first-run shape
                /* skipTests */ false,
                false,
                false,
                false,
                java.util.Set.of(),
                dev.jkbuild.config.SessionContext.current());
        Goal goal = BuildPipeline.coreBuilder(in).build();
        GoalResult result = goal.run();
        for (GoalResult.Diagnostic d : result.errors()) {
            System.out.println("DIAG [" + d.phase() + "]: " + d.message());
        }
        assertThat(result.errors()).isEmpty();
        assertThat(result.success())
                .as("first build with an uninstalled pin runs its test on the pinned JDK")
                .isTrue();
    }
}
