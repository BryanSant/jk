// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InitScaffolderTest {

    @Test
    void library_java_writes_package_info(@TempDir Path tempDir) throws IOException {
        InitScaffolder.write(library(tempDir, InitInputs.Language.JAVA, true));

        var pkgInfo = tempDir.resolve("src/main/java/com/example/package-info.java");
        assertThat(pkgInfo).exists();
        assertThat(Files.readString(pkgInfo)).contains("package com.example;");
    }

    @Test
    void runnable_java_writes_app_main(@TempDir Path tempDir) throws IOException {
        InitScaffolder.write(runnable(tempDir, InitInputs.Language.JAVA, "com.example.App"));

        var app = tempDir.resolve("src/main/java/com/example/App.java");
        assertThat(app).exists();
        var body = Files.readString(app);
        assertThat(body).contains("package com.example;");
        assertThat(body).contains("public final class App");
        assertThat(body).contains("public static void main(String[] args)");
        assertThat(body).contains("Hello, world!");
    }

    @Test
    void runnable_kotlin_writes_kt_app(@TempDir Path tempDir) throws IOException {
        InitScaffolder.write(runnable(tempDir, InitInputs.Language.KOTLIN, "com.example.App"));

        var app = tempDir.resolve("src/main/kotlin/com/example/App.kt");
        assertThat(app).exists();
        var body = Files.readString(app);
        assertThat(body).contains("package com.example");
        assertThat(body).contains("fun main()");
        assertThat(body).contains("Hello, world!");
    }

    @Test
    void library_kotlin_writes_kt_package_marker(@TempDir Path tempDir) throws IOException {
        InitScaffolder.write(library(tempDir, InitInputs.Language.KOTLIN, true));

        var marker = tempDir.resolve("src/main/kotlin/com/example/PackageInfo.kt");
        assertThat(marker).exists();
        assertThat(Files.readString(marker)).isEqualTo("package com.example\n");
    }

    @Test
    void deps_renders_into_dependencies_main_block(@TempDir Path tempDir) throws IOException {
        var inputs = new InitInputs(
                "com.example", "widget", "25",
                Optional.empty(), false, false,
                InitInputs.Language.JAVA,
                List.of("commons-io", "guava"),
                false, tempDir);
        InitScaffolder.write(inputs);

        var build = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(build).contains("[dependencies]");
        assertThat(build).contains("main = [");
        assertThat(build).contains("\"commons-io:commons-io:2.16.1\"");
        assertThat(build).contains("\"com.google.guava:guava:33.4.0-jre\"");
        assertThat(build).doesNotContain("processor =");
        assertThat(build).doesNotContain("provided =");
    }

    @Test
    void lombok_adds_processor_and_provided_blocks(@TempDir Path tempDir) throws IOException {
        var inputs = new InitInputs(
                "com.example", "widget", "25",
                Optional.empty(), false, false,
                InitInputs.Language.JAVA,
                List.of("lombok"),
                false, tempDir);
        InitScaffolder.write(inputs);

        var build = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(build).doesNotContain("main = [");
        assertThat(build).contains("processor = [");
        assertThat(build).contains("provided = [");
        assertThat(build).contains("\"org.projectlombok:lombok:1.18.34\"");
    }

    @Test
    void shadow_only_set_when_true(@TempDir Path tempDir) throws IOException {
        var off = new InitInputs(
                "com.example", "widget", "25",
                Optional.of("com.example.App"), false, false,
                InitInputs.Language.JAVA, List.of(), false, tempDir);
        InitScaffolder.write(off);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).doesNotContain("shadow");

        var sub = Files.createDirectories(tempDir.resolve("on"));
        var on = new InitInputs(
                "com.example", "widget", "25",
                Optional.of("com.example.App"), true, false,
                InitInputs.Language.JAVA, List.of(), false, sub);
        InitScaffolder.write(on);
        assertThat(Files.readString(sub.resolve("jk.toml"))).contains("shadow   = true");
    }

    @Test
    void native_only_set_when_true(@TempDir Path tempDir) throws IOException {
        var off = new InitInputs(
                "com.example", "widget", "25",
                Optional.empty(), false, false,
                InitInputs.Language.JAVA, List.of(), false, tempDir);
        InitScaffolder.write(off);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).doesNotContain("native");

        var sub = Files.createDirectories(tempDir.resolve("on"));
        var on = new InitInputs(
                "com.example", "widget", "25",
                Optional.empty(), false, true,
                InitInputs.Language.JAVA, List.of(), false, sub);
        InitScaffolder.write(on);
        assertThat(Files.readString(sub.resolve("jk.toml"))).contains("native   = true");
    }

    @Test
    void no_sample_skips_source_tree(@TempDir Path tempDir) throws IOException {
        InitScaffolder.write(library(tempDir, InitInputs.Language.JAVA, false));

        assertThat(tempDir.resolve("jk.toml")).exists();
        assertThat(tempDir.resolve("jk.lock")).exists();
        assertThat(tempDir.resolve("src")).doesNotExist();
    }

    private static InitInputs library(Path dir, InitInputs.Language lang, boolean sample) {
        return new InitInputs(
                "com.example", "widget", "25",
                Optional.empty(), false, false,
                lang, List.of(), sample, dir);
    }

    private static InitInputs runnable(Path dir, InitInputs.Language lang, String main) {
        return new InitInputs(
                "com.example", "widget", "25",
                Optional.of(main), false, false,
                lang, List.of(), true, dir);
    }
}
