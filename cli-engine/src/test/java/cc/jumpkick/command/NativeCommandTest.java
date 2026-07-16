// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Native builds are opt-in: {@code jk native} only builds modules that set {@code native = true}. A
 * project without it is refused up front (before any build), so we can assert the guard without
 * needing GraalVM in tests.
 */
class NativeCommandTest {

    @Test
    void refuses_a_project_that_is_not_native_eligible(@TempDir Path tempDir) throws Exception {
        // Has a main class but no `native = true` → not eligible → refuse.
        Files.writeString(
                tempDir.resolve("jk.toml"),
                "[project]\ngroup = \"com.example\"\nname = \"widget\"\nversion = \"0.1.0\"\n"
                        + "java = 21\nmain = \"example.Hello\"\n");
        Path src = tempDir.resolve("src/main/java/example/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(
                src, "package example;\npublic class Hello {" + " public static void main(String[] a) {} }\n");

        int exit = run(
                "native",
                "-C",
                tempDir.toString(),
                "--cache-dir",
                tempDir.resolve("cache").toString());

        // Opt-in guard: not eligible without native = true → exit 2, before any
        // build (no jar produced).
        assertThat(exit).isEqualTo(2);
        assertThat(tempDir.resolve("target/widget-0.1.0.jar")).doesNotExist();
    }

    private static int run(String... args) {
        return Jk.execute(args);
    }
}
