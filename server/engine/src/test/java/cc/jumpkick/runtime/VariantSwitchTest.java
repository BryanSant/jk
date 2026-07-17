// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * In-place variant switches on a Kotlin module (docs/variants.md → "Switching variants in
 * place"): the dropped value's extra-src classes must not survive the switch — kotlinc's IC
 * prunes its own dir, but the assemble merge into {@code classes/} is additive, so the merged
 * tree restarts clean when the source set shrinks ({@code FreshnessStamp.hasRemovedSources}).
 *
 * <p>Network test (Maven Central; kotlinc worker via the test JVM's worker-jar property); the
 * CAS persists under build/ so repeat runs are warm.
 */
class VariantSwitchTest {

    @Test
    void switching_variants_drops_the_previous_values_extra_src_classes(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "ktswitch"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                kotlin  = "^2.4.0"
                layout  = "simple"

                [variants.mode.a]
                extra-src = ["src-a"]
                [variants.mode.b]
                extra-src = ["src-b"]

                # No tests here; the pin keeps the injected junit out of the graph (see
                # KotlinSerializationTest for the rationale).
                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src/Main.kt"), """
                package com.example.sw
                fun main() = println("switch")
                """);
        Files.createDirectories(project.resolve("src-a"));
        Files.writeString(project.resolve("src-a/OnlyA.kt"), """
                package com.example.sw
                class OnlyA
                """);
        Files.createDirectories(project.resolve("src-b"));
        Files.writeString(project.resolve("src-b/OnlyB.kt"), """
                package com.example.sw
                class OnlyB
                """);

        var parsed = cc.jumpkick.config.JkBuildParser.parse(project.resolve("jk.toml"));
        Pipeline lock = LockPipelines.lockPipeline(
                project, parsed, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().errors()).isEmpty();

        Path classes = project.resolve("target/classes/main/com/example/sw");
        PipelineResult a = buildVariant(project, cache, "mode=a");
        assertThat(a.errors()).isEmpty();
        assertThat(a.success()).as("variant a builds").isTrue();
        assertThat(classes.resolve("OnlyA.class")).exists();
        assertThat(classes.resolve("OnlyB.class")).doesNotExist();

        PipelineResult b = buildVariant(project, cache, "mode=b");
        assertThat(b.errors()).isEmpty();
        assertThat(b.success()).as("variant b builds after a").isTrue();
        assertThat(classes.resolve("OnlyB.class")).exists();
        assertThat(classes.resolve("OnlyA.class"))
                .as("the dropped value's class must not survive the switch")
                .doesNotExist();

        // And back again — both directions stay clean.
        PipelineResult a2 = buildVariant(project, cache, "mode=a");
        assertThat(a2.success()).as("variant a builds after b").isTrue();
        assertThat(classes.resolve("OnlyA.class")).exists();
        assertThat(classes.resolve("OnlyB.class")).doesNotExist();

        // The daemon-observed failing shape: clean, restore a from the action cache, then
        // switch to b — the switch after a RESTORED (not compiled) tree must also work.
        cc.jumpkick.util.PathUtil.deleteRecursively(project.resolve("target"));
        PipelineResult a3 = buildVariant(project, cache, "mode=a");
        assertThat(a3.success()).as("variant a restores after clean").isTrue();
        assertThat(classes.resolve("OnlyA.class")).exists();
        PipelineResult b2 = buildVariant(project, cache, "mode=b");
        assertThat(b2.errors()).isEmpty();
        assertThat(b2.success()).as("variant b builds after a cache-restored a").isTrue();
        assertThat(classes.resolve("OnlyB.class")).exists();
        assertThat(classes.resolve("OnlyA.class")).doesNotExist();
    }

    private static PipelineResult buildVariant(Path project, Path cache, String selection) {
        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
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
                        cc.jumpkick.config.SessionContext.current())
                .withVariant(selection, java.util.Map.of());
        Pipeline pipeline = BuildPipelines.coreBuilder(in).build();
        PipelineResult result = pipeline.run();
        for (PipelineResult.Diagnostic d : result.errors()) {
            System.out.println("DIAG [" + d.step() + "]: " + d.message());
        }
        return result;
    }
}
