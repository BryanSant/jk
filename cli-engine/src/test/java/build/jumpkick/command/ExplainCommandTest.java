// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.cli.Jk;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk explain} renders the unified composite build plan (BuildGraph). */
class ExplainCommandTest {

    private static void project(Path dir, String name, String... deps) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("""
                [project]
                group   = "com.example"
                name    = "%s"
                version = "1.0.0"
                jdk     = 21
                java    = 21
                """.formatted(name));
        if (deps.length > 0) {
            // Sibling modules are declared by coordinate (inline path deps were removed); the
            // workspace root resolves them to the local module builds.
            sb.append("\n[dependencies]\n");
            for (String d : deps) {
                sb.append("%s = { group = \"com.example\", name = \"%s\", version = \"1.0.0\" }\n".formatted(d, d));
            }
        }
        Files.writeString(dir.resolve("jk.toml"), sb.toString());
    }

    /** A workspace-root jk.toml listing the given modules. */
    private static void workspace(Path dir, String... modules) throws IOException {
        Files.createDirectories(dir);
        String mods = String.join(", ", Arrays.stream(modules).map(m -> '"' + m + '"').toList());
        Files.writeString(
                dir.resolve("jk.toml"),
                """
                [project]
                group   = "com.example"
                name    = "root"
                version = "1.0.0"

                [workspace]
                modules = [%s]
                """
                        .formatted(mods));
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
        assertThat(elided).matches(".*…\\+\\d+ more…$"); // ends with …+<count> more…
        assertThat(elided.length()).isLessThan(full.length());
        // Count = units that didn't fit (the leading ones shown are excluded).
        int shown = (int)
                Arrays.stream(elided.split(", ")).filter(s -> s.startsWith(":")).count();
        assertThat(elided).contains("…+" + (units.size() - shown) + " more…");

        // A single prereq is never elided.
        assertThat(ExplainCommand.elideDeps(List.of(":only"), 1)).isEqualTo(":only");
    }

    @Test
    void wrapNames_packs_tokens_into_width_bounded_lines() {
        List<String> tokens = List.of(":model", ":plugin-api", ":core", ":io", ":auditor");

        // Unbounded (the non-TTY MAX_VALUE) → the whole list on one line.
        assertThat(ExplainCommand.wrapNames(tokens, Integer.MAX_VALUE)).containsExactly(String.join(", ", tokens));

        // Narrow → several lines, each within the budget; every token kept, order preserved.
        List<String> lines = ExplainCommand.wrapNames(tokens, 20);
        assertThat(lines.size()).isGreaterThan(1);
        for (String line : lines) assertThat(line.length()).isLessThanOrEqualTo(20);
        assertThat(String.join(", ", lines)).isEqualTo(String.join(", ", tokens));
    }

    @Test
    void renders_units_in_dependency_order(@TempDir Path tmp) throws Exception {
        // A two-module workspace: app depends on sibling module lib by coordinate.
        workspace(tmp, "lib", "app");
        project(tmp.resolve("lib"), "lib");
        project(tmp.resolve("app"), "app", "lib");

        // Assertions target single-color-span tokens, so they hold whether or not ANSI
        // color is active (each coordinate segment is one colorize call).
        String out = runExplainCapturingStdout(tmp);

        assertThat(out).contains("Modules: 2");
        assertThat(out).contains("com.example").contains("app").contains("lib");
        // Both modules rebuild (fresh), listed dependency-first: lib before app.
        assertThat(out.indexOf("lib")).isLessThan(out.lastIndexOf("app"));
    }
}
