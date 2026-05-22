// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.model.BuildJk;
import dev.buildjk.model.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AddRemoveCommandTest {

    @Test
    void add_modifies_build_jk(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        int exit = run("add", "com.fasterxml.jackson.core:jackson-databind:2.18.2",
                "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        BuildJk parsed = BuildJkParser.parse(tempDir.resolve("build.jk"));
        assertThat(parsed.dependencies().of(Scope.MAIN))
                .singleElement()
                .satisfies(d -> assertThat(d.module())
                        .isEqualTo("com.fasterxml.jackson.core:jackson-databind"));
    }

    @Test
    void add_test_scope(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        int exit = run("add", "org.junit.jupiter:junit-jupiter:6.1.0", "--test",
                "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        BuildJk parsed = BuildJkParser.parse(tempDir.resolve("build.jk"));
        assertThat(parsed.dependencies().of(Scope.TEST)).hasSize(1);
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void add_then_remove_cycle(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        run("add", "com.foo:bar:1.0", "-C", tempDir.toString());
        int exit = run("remove", "com.foo:bar", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(0);

        BuildJk parsed = BuildJkParser.parse(tempDir.resolve("build.jk"));
        assertThat(parsed.dependencies().of(Scope.MAIN)).isEmpty();
    }

    @Test
    void add_rejects_unparseable_coord(@TempDir Path tempDir) throws Exception {
        run("init", tempDir.toString());
        int exit = run("add", "not-a-coord", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(64);
    }

    @Test
    void add_without_build_jk_fails(@TempDir Path tempDir) {
        int exit = run("add", "com.foo:bar:1.0", "-C", tempDir.toString());
        assertThat(exit).isEqualTo(2);
    }

    private static int run(String... args) {
        return new CommandLine(new Jk()).execute(args);
    }
}
