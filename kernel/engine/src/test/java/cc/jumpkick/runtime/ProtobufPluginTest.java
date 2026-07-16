// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Step 5, blocker 3: the protobuf plugin — protoc codegen before compile. A plain
 * Java project (deliberately: the plugin is ecosystem-neutral) declares {@code [protobuf]}, the
 * engine fetches the per-OS protoc binary ({@code ${host.os-arch}@exe} step-dependency), the
 * plugin worker forks it over {@code proto/}, and the generated Java compiles and packages like
 * any contributed source. Compiling a reference to the generated builder IS the acceptance.
 *
 * <p>Network test (Maven Central; protobuf worker via the test JVM's worker-jar property); the
 * CAS persists under build/ so repeat runs are warm.
 */
class ProtobufPluginTest {

    @Test
    void proto_messages_generate_compile_and_package(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "pbdemo"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [protobuf]
                version = "4.33.1"

                [dependencies]
                protobuf-java = { group = "com.google.protobuf", name = "protobuf-java", version = "=4.33.1" }

                # This project runs no tests; owning [test-dependencies] keeps the injected
                # junit-jupiter "latest" out of the graph and the lock deterministic
                # (see KotlinSerializationTest).
                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path protoDir = Files.createDirectories(project.resolve("proto"));
        Files.writeString(protoDir.resolve("greeting.proto"), """
                syntax = "proto3";
                package demo;

                option java_package = "com.example.pb";
                option java_multiple_files = true;

                message Greeting {
                  string message = 1;
                }
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/pb"));
        Files.writeString(src.resolve("Main.java"), """
                package com.example.pb;

                public final class Main {
                    public static void main(String[] args) {
                        // Greeting only exists if protoc ran and its output joined the source set.
                        Greeting g = Greeting.newBuilder().setMessage("hi").build();
                        System.out.println(g.getMessage());
                    }
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        assertThat(build.pluginConfig("protobuf")).isPresent();

        Pipeline lock = LockPipelines.lockPipeline(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        PipelineResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

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
                SessionContext.current());
        Pipeline pipeline = BuildPipelines.coreBuilder(in).build();
        PipelineResult result = pipeline.run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(anyFile(project.resolve("target"), "Greeting.class"))
                .as("protoc-generated Greeting compiled")
                .isTrue();
        assertThat(anyFile(project.resolve("target"), "Main.class"))
                .as("project source referencing the generated type compiled")
                .isTrue();
    }

    /**
     * The Kotlin-DSL shape (NiA's datastore-proto): {@code kotlin = true} emits
     * {@code --kotlin_out} alongside {@code --java_out}; the generated .kt wraps the generated
     * Java message, so a Kotlin-only module must route through the mixed pipeline (javac compiles
     * the contributed Java, kotlinc reads it from source via -Xjava-source-roots).
     */
    @Test
    void kotlin_dsl_codegen_compiles_in_a_kotlin_module(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");

        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "pbkt"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                kotlin  = "^2.4.0"
                layout  = "simple"

                [protobuf]
                version = "4.29.2"
                lite    = true
                kotlin  = true

                [dependencies]
                protobuf-kotlin-lite = { group = "com.google.protobuf", name = "protobuf-kotlin-lite", version = "=4.29.2" }

                [test-dependencies]
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=6.1.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);
        Path protoDir = Files.createDirectories(project.resolve("proto"));
        Files.writeString(protoDir.resolve("greeting.proto"), """
                syntax = "proto3";
                package demo;

                option java_package = "com.example.pbkt";
                option java_multiple_files = true;

                message Greeting {
                  string message = 1;
                }
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/pbkt"));
        Files.writeString(src.resolve("Main.kt"), """
                package com.example.pbkt

                fun main() {
                    // The `greeting {}` DSL only exists when --kotlin_out ran; it wraps the
                    // generated Java Greeting, so both codegens must have compiled.
                    val g = greeting { message = "hi" }
                    println(g.message)
                }
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Pipeline lock = LockPipelines.lockPipeline(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        assertThat(lock.run().errors()).isEmpty();

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
                SessionContext.current());
        PipelineResult result = BuildPipelines.coreBuilder(in).build().run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        assertThat(anyFile(project.resolve("target"), "GreetingKt.class"))
                .as("protoc Kotlin DSL compiled")
                .isTrue();
        assertThat(anyFile(project.resolve("target"), "Greeting.class"))
                .as("protoc Java message compiled (mixed routing)")
                .isTrue();
        assertThat(anyFile(project.resolve("target"), "MainKt.class"))
                .as("project Kotlin referencing both compiled")
                .isTrue();
    }

    private static boolean anyFile(Path root, String nameFragment) throws java.io.IOException {
        if (!Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            return walk.anyMatch(f -> f.getFileName().toString().contains(nameFragment));
        }
    }
}
