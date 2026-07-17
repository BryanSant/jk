// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the dashboard's headless event-folding suite ({@code src/test/js/fold.test.mjs}) with
 * {@code node --test} — skipped when Node isn't installed (Node is a test-time convenience, never
 * a build dependency; see {@code docs/webclient.md}). {@code fold.js} is copied to a {@code .mjs}
 * so Node treats it as the ES module it is.
 */
class WebClientFoldTest {

    @Test
    void fold_logic_passes_the_node_test_suite(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "node not installed — skipping the JS fold suite");

        Path foldJs = Path.of("src/main/resources/www/fold.js");
        Path testMjs = Path.of("src/test/js/fold.test.mjs");
        assertThat(foldJs).exists();
        assertThat(testMjs).exists();
        Path foldMjs = tempDir.resolve("fold.mjs");
        Files.copy(foldJs, foldMjs);

        ProcessBuilder builder = new ProcessBuilder(
                        "node", "--test", testMjs.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .directory(tempDir.toFile());
        builder.environment().put("JK_FOLD_MJS", foldMjs.toAbsolutePath().toString());
        Process node = builder.start();
        String output = new String(node.getInputStream().readAllBytes());
        assertThat(node.waitFor(30, TimeUnit.SECONDS))
                .as("node --test finished")
                .isTrue();
        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output).contains("fail 0"); // both node reporters ("# fail 0" TAP, "ℹ fail 0" spec)
    }

    private static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
