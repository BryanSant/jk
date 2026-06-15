// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.Jk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code jk explain} renders the unified composite build plan (BuildGraph). */
class ExplainCommandTest {

    private static void project(Path dir, String name, String... pathDeps) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("""
                [project]
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                jdk     = 21
                java    = 21
                """.formatted(name));
        if (pathDeps.length > 0) {
            sb.append("\n[dependencies.main]\n");
            for (String d : pathDeps) {
                sb.append("%s = { group = \"com.example\", name = \"%s\", path = \"../%s\" }\n"
                        .formatted(d, d, d));
            }
        }
        Files.writeString(dir.resolve("jk.toml"), sb.toString());
    }

    private static String runExplainCapturingStdout(Path dir) {
        PrintStream orig = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(ps);
            int exit = Jk.execute(new String[] {"explain", "-C", dir.toString()});
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(orig);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void renders_units_origins_and_edges_in_dependency_order(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("lib"), "lib");
        project(tmp.resolve("app"), "app", "lib");

        // Assertions target single-color-span tokens, so they hold whether or not
        // ANSI color is active (coords / labels / edges are each one colorize call).
        String out = runExplainCapturingStdout(tmp.resolve("app"));

        assertThat(out).contains("2 units, dependency order");
        assertThat(out).contains("com.example:app").contains("[root]");
        assertThat(out).contains("com.example:lib").contains("[path dep]");
        assertThat(out).contains("← com.example:lib");          // app's edge to lib
        // lib (the unit) is printed before app (the root) — dependency-first order.
        assertThat(out.indexOf("com.example:lib")).isLessThan(out.indexOf("[root]"));
    }
}
