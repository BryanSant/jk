// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AddRemoveCommandTest {

    @Test
    void add_modifies_build_jk(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run("add", "com.fasterxml.jackson.core:jackson-databind:2.18.2",
                "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.name()).isEqualTo("jackson-databind");
                    assertThat(d.module())
                            .isEqualTo("com.fasterxml.jackson.core:jackson-databind");
                });
    }

    @Test
    void add_test_scope(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run("add", "org.junit.jupiter:junit-jupiter:6.1.0", "--test",
                "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void add_with_structured_flags(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        int exit = run("add", "spring-web",
                "--group", "org.springframework.boot",
                "--artifact", "spring-boot-starter-web",
                "--ver", "3.4.0",
                "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        String toml = Files.readString(tempDir.resolve("jk.toml"));
        assertThat(toml).contains(
                "spring-web = { group = \"org.springframework.boot\", "
                        + "artifact = \"spring-boot-starter-web\", version = \"3.4.0\" }");

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.name()).isEqualTo("spring-web");
                    assertThat(d.module())
                            .isEqualTo("org.springframework.boot:spring-boot-starter-web");
                });
    }

    @Test
    void add_then_remove_by_name(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        run("add", "com.foo:bar:1.0", "-C", tempDir.toString());
        int exit = run("remove", "bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void remove_accepts_coord_form_as_migration_aid(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        run("add", "com.foo:bar:1.0", "-C", tempDir.toString());
        int exit = run("remove", "com.foo:bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        JkBuild parsed = JkBuildParser.parse(tempDir.resolve("jk.toml"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void add_rejects_unparseable_coord(@TempDir Path tempDir) throws Exception {
        run("new", tempDir.toString());
        // A bare name without --group / --ver is a usage error.
        int exit = run("add", "not-a-coord", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void add_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run("add", "com.foo:bar:1.0", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(2);
    }

    @Test
    void add_unknown_bare_name_suggests_close_registry_matches(@TempDir Path tempDir) throws Exception {
        // `picocl` is a typo for `picocli`, which IS in the bundled
        // registry. The "not in registry" error should surface the
        // suggestion via AliasRegistry.suggestionsFor — matching the
        // did-you-mean behavior of the parser.
        run("new", tempDir.toString());

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = run("add", "picocl", "--ver", "1.0", "-C", tempDir.toString());
        } finally {
            System.setErr(origErr);
        }
        assertThat(exit).isEqualTo(64);
        String msg = stderr.toString(StandardCharsets.UTF_8);
        assertThat(msg).contains("not in the registry");
        assertThat(msg).contains("Did you mean:");
        assertThat(msg).contains("picocli");
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
