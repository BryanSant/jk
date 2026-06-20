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
import java.util.List;

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
    void elideDeps_fits_width_with_remaining_count_marker() {
        List<String> units = List.of(":engine", ":core", ":io", ":plugin-api", ":resolver", ":git-client");
        String full = String.join(", ", units);

        // Wide budget (and the non-TTY MAX_VALUE) → the full list, no marker.
        assertThat(ExplainCommand.elideDeps(units, 500)).isEqualTo(full);
        assertThat(ExplainCommand.elideDeps(units, Integer.MAX_VALUE)).isEqualTo(full);

        // Narrow budget → leading units that fit, then a "…+N more…" remaining-count marker.
        String elided = ExplainCommand.elideDeps(units, 40);
        assertThat(elided).startsWith(":engine");
        assertThat(elided).matches(".*…\\+\\d+ more…$");      // ends with …+<count> more…
        assertThat(elided.length()).isLessThan(full.length());
        // Count = units that didn't fit (the leading ones shown are excluded).
        int shown = (int) java.util.Arrays.stream(elided.split(", "))
                .filter(s -> s.startsWith(":")).count();
        assertThat(elided).contains("…+" + (units.size() - shown) + " more…");

        // A single prereq is never elided.
        assertThat(ExplainCommand.elideDeps(List.of(":only"), 1)).isEqualTo(":only");
    }

    @Test
    void renders_units_origins_and_edges_in_dependency_order(@TempDir Path tmp) throws Exception {
        project(tmp.resolve("lib"), "lib");
        project(tmp.resolve("app"), "app", "lib");

        // Assertions target single-color-span tokens, so they hold whether or not
        // ANSI color is active (coords / labels / edges are each one colorize call).
        String out = runExplainCapturingStdout(tmp.resolve("app"));

        assertThat(out).contains("2 modules");
        assertThat(out).contains("com.example:app").contains("root");
        assertThat(out).contains("path dep");
        assertThat(out).contains("←").contains("lib");          // app's edge to lib (abbreviated)
        // lib (the path dep) is printed before app (the root) — dependency-first order.
        assertThat(out.indexOf("path dep")).isLessThan(out.indexOf("root"));
    }
}
