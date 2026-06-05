// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NewScaffolderTest {

    @Test
    void library_java_writes_package_info(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(library(tempDir, NewInputs.Language.JAVA, true, 25));

        var pkgInfo = tempDir.resolve("src/main/java/com/example/package-info.java");
        assertThat(pkgInfo).exists();
        assertThat(Files.readString(pkgInfo)).contains("package com.example;");
    }

    @Test
    void runnable_java_25_uses_instance_main(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(runnable(tempDir, NewInputs.Language.JAVA, "com.example.Main", 25, false));

        var main = tempDir.resolve("src/main/java/com/example/Main.java");
        assertThat(main).exists();
        var body = Files.readString(main);
        assertThat(body).contains("package com.example;");
        assertThat(body).contains("class Main");
        // JEP 512: no `public final`, no `static`, no `String[] args`.
        assertThat(body).doesNotContain("public final class");
        assertThat(body).doesNotContain("public static void main");
        assertThat(body).contains("void main(String... args)");
        assertThat(body).contains("IO.println(\"Hello, world!\")");
    }

    @Test
    void runnable_java_pre_25_uses_traditional_main(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(runnable(tempDir, NewInputs.Language.JAVA, "com.example.Main", 21, false));

        var main = tempDir.resolve("src/main/java/com/example/Main.java");
        assertThat(main).exists();
        var body = Files.readString(main);
        assertThat(body).contains("public final class Main");
        assertThat(body).contains("public static void main(String[] args)");
        assertThat(body).contains("System.out.println(\"Hello, world!\")");
        assertThat(body).doesNotContain("IO.println");
    }

    @Test
    void runnable_kotlin_non_compact_writes_packaged_main(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(runnable(tempDir, NewInputs.Language.KOTLIN, "com.example.MainKt", 25, false));

        var main = tempDir.resolve("src/main/kotlin/com/example/Main.kt");
        assertThat(main).exists();
        var body = Files.readString(main);
        assertThat(body).contains("package com.example");
        assertThat(body).contains("fun main()");
        assertThat(body).contains("Hello, world!");
    }

    @Test
    void runnable_kotlin_compact_writes_unpackaged_main_under_src(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(runnable(tempDir, NewInputs.Language.KOTLIN, "MainKt", 25, true));

        var main = tempDir.resolve("src/Main.kt");
        assertThat(main).exists();
        var body = Files.readString(main);
        assertThat(body).doesNotContain("package ");
        assertThat(body).contains("fun main()");
        // The conventional layout should not also be present.
        assertThat(tempDir.resolve("src/main/kotlin")).doesNotExist();
    }

    @Test
    void library_kotlin_writes_kt_package_marker(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(library(tempDir, NewInputs.Language.KOTLIN, true, 25));

        var marker = tempDir.resolve("src/main/kotlin/com/example/PackageInfo.kt");
        assertThat(marker).exists();
        assertThat(Files.readString(marker)).isEqualTo("package com.example\n");
    }

    @Test
    void deps_render_in_name_as_key_subtables(@TempDir Path tempDir) throws IOException {
        var inputs = inputs(tempDir, NewInputs.Language.JAVA, "widget",
                Optional.empty(), false, false,
                List.of("commons-io", "guava"), 25, false, Optional.empty());
        NewScaffolder.write(inputs);

        var build = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(build).contains("[dependencies.main]");
        // Catalog-known short names collapse to the `name = "latest"` one-liner.
        assertThat(build).contains("commons-io = \"latest\"");
        assertThat(build).contains("guava = \"latest\"");
        assertThat(build).doesNotContain("[dependencies.processor]");
        assertThat(build).doesNotContain("[dependencies.provided]");
    }

    @Test
    void lombok_adds_processor_and_provided_blocks(@TempDir Path tempDir) throws IOException {
        var inputs = inputs(tempDir, NewInputs.Language.JAVA, "widget",
                Optional.empty(), false, false,
                List.of("lombok"), 25, false, Optional.empty());
        NewScaffolder.write(inputs);

        var build = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(build).doesNotContain("[dependencies.main]");
        assertThat(build).contains("[dependencies.processor]");
        assertThat(build).contains("[dependencies.provided]");
        assertThat(build).contains("lombok = \"latest\"");
    }

    @Test
    void jspecify_renders_into_main_scope(@TempDir Path tempDir) throws IOException {
        var inputs = inputs(tempDir, NewInputs.Language.JAVA, "widget",
                Optional.empty(), false, false,
                List.of("jspecify"), 25, false, Optional.empty());
        NewScaffolder.write(inputs);

        var build = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(build).contains("[dependencies.main]");
        assertThat(build).contains("jspecify = \"latest\"");
    }

    @Test
    void kotest_renders_into_test_scope(@TempDir Path tempDir) throws IOException {
        var inputs = inputs(tempDir, NewInputs.Language.KOTLIN, "widget",
                Optional.empty(), false, false,
                List.of("kotest"), 25, false, Optional.empty());
        NewScaffolder.write(inputs);

        var build = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(build).contains("[dependencies.test]");
        assertThat(build).contains("kotest-runner-junit6 = \"latest\"");
    }

        @Test
    void kotlin_compact_and_module_emit_project_fields(@TempDir Path tempDir) throws IOException {
        var inputs = inputs(tempDir, NewInputs.Language.KOTLIN, "widget",
                Optional.of("MainKt"), false, false,
                List.of(), 25, true, Optional.of("widget-core"));
        NewScaffolder.write(inputs);

        var build = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(build).contains("compact  = true");
        assertThat(build).contains("module   = \"widget-core\"");
    }

    @Test
    void shadow_only_set_when_true(@TempDir Path tempDir) throws IOException {
        var off = inputs(tempDir, NewInputs.Language.JAVA, "widget",
                Optional.of("com.example.Main"), false, false,
                List.of(), 25, false, Optional.empty());
        NewScaffolder.write(off);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).doesNotContain("shadow");

        var sub = Files.createDirectories(tempDir.resolve("on"));
        var on = inputs(sub, NewInputs.Language.JAVA, "widget",
                Optional.of("com.example.Main"), true, false,
                List.of(), 25, false, Optional.empty());
        NewScaffolder.write(on);
        assertThat(Files.readString(sub.resolve("jk.toml"))).contains("shadow   = true");
    }

    @Test
    void native_only_set_when_true(@TempDir Path tempDir) throws IOException {
        var off = inputs(tempDir, NewInputs.Language.JAVA, "widget",
                Optional.empty(), false, false,
                List.of(), 25, false, Optional.empty());
        NewScaffolder.write(off);
        assertThat(Files.readString(tempDir.resolve("jk.toml"))).doesNotContain("native");

        var sub = Files.createDirectories(tempDir.resolve("on"));
        var on = inputs(sub, NewInputs.Language.JAVA, "widget",
                Optional.empty(), false, true,
                List.of(), 25, false, Optional.empty());
        NewScaffolder.write(on);
        assertThat(Files.readString(sub.resolve("jk.toml"))).contains("native   = true");
    }

    @Test
    void no_sample_skips_source_tree(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(library(tempDir, NewInputs.Language.JAVA, false, 25));

        assertThat(tempDir.resolve("jk.toml")).exists();
        assertThat(tempDir.resolve("jk.lock")).exists();
        assertThat(tempDir.resolve("src")).doesNotExist();
    }

    @Test
    void resolved_jdk_identifier_is_stamped_into_lockfile(@TempDir Path tempDir) throws IOException {
        var inputs = new NewInputs(
                "com.example", "widget", "25", 25,
                Optional.of("temurin-25.0.3"),
                Optional.of("com.example.Main"), false, false,
                NewInputs.Language.JAVA, false, Optional.empty(),
                List.of(), true, tempDir);
        NewScaffolder.write(inputs);

        var lock = Files.readString(tempDir.resolve("jk.lock"));
        assertThat(lock).contains("jdk = \"temurin-25.0.3\"");
        assertThat(lock).contains("version = 1");
    }

    @Test
    void scaffolder_writes_gitignore_covering_build_outputs(@TempDir Path tempDir) throws IOException {
        NewScaffolder.write(library(tempDir, NewInputs.Language.JAVA, false, 25));

        Path gitignore = tempDir.resolve(".gitignore");
        assertThat(gitignore).exists();
        String body = Files.readString(gitignore);
        assertThat(body).contains("target/");
        assertThat(body).contains("**/build/");
        assertThat(body).contains(".jk/");
    }

    @Test
    void scaffolder_preserves_existing_gitignore(@TempDir Path tempDir) throws IOException {
        Path gitignore = tempDir.resolve(".gitignore");
        Files.createDirectories(tempDir);
        Files.writeString(gitignore, "# pre-existing content\nnode_modules/\n");

        NewScaffolder.write(library(tempDir, NewInputs.Language.JAVA, false, 25));

        // jk doesn't overwrite an existing .gitignore — user customization wins.
        assertThat(Files.readString(gitignore)).isEqualTo("# pre-existing content\nnode_modules/\n");
    }

    @Test
    void absent_jdk_identifier_leaves_no_jdk_line(@TempDir Path tempDir) throws IOException {
        var inputs = new NewInputs(
                "com.example", "widget", "25", 25,
                Optional.empty(),
                Optional.empty(), false, false,
                NewInputs.Language.JAVA, false, Optional.empty(),
                List.of(), false, tempDir);
        NewScaffolder.write(inputs);

        var lock = Files.readString(tempDir.resolve("jk.lock"));
        assertThat(lock).doesNotContain("jdk = \"");
    }

    private static NewInputs library(Path dir, NewInputs.Language lang, boolean sample, int major) {
        return new NewInputs(
                "com.example", "widget", String.valueOf(major), major,
                Optional.empty(),
                Optional.empty(), false, false,
                lang, false, Optional.empty(),
                List.of(), sample, dir);
    }

    private static NewInputs runnable(Path dir, NewInputs.Language lang, String main, int major, boolean kotlinCompact) {
        return new NewInputs(
                "com.example", "widget", String.valueOf(major), major,
                Optional.empty(),
                Optional.of(main), false, false,
                lang, kotlinCompact, Optional.empty(),
                List.of(), true, dir);
    }

    private static NewInputs inputs(
            Path dir,
            NewInputs.Language lang,
            String name,
            Optional<String> main,
            boolean shadow,
            boolean nativeImage,
            List<String> deps,
            int major,
            boolean kotlinCompact,
            Optional<String> kotlinModule) {
        return new NewInputs(
                "com.example", name, String.valueOf(major), major,
                Optional.empty(),
                main, shadow, nativeImage,
                lang, kotlinCompact, kotlinModule,
                deps, false, dir);
    }
}
