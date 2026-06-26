// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link PluginLoader}'s fork dispatch end-to-end: it launches the
 * worker as {@code <java> -cp <jar> PluginWorkerMain <args>}, which ServiceLoad-s
 * the plugin and runs it. (jk has no in-process plugin path — every worker is
 * its own JVM.)
 */
class PluginLoaderTest {

    /** The java-compiler worker jar, side-loaded by Gradle via this system property. */
    private static Path javaCompilerJar() {
        String prop = System.getProperty("jk.java.worker.jar");
        Assumptions.assumeTrue(prop != null, "jk.java.worker.jar not set — skipping fork test");
        return Path.of(prop);
    }

    private static Path javaExe() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java");
    }

    @Test
    void forks_a_worker_and_a_bad_spec_exits_nonzero() throws Exception {
        Path jar = javaCompilerJar();
        List<String> protocol = new ArrayList<>();
        List<String> passthrough = new ArrayList<>();
        // The forked worker loads the java-compiler plugin and is handed a
        // nonexistent spec file — it must exit non-zero, proving the
        // `-cp <jar> PluginWorkerMain` dispatch actually reached the plugin.
        int code = PluginLoader.run(
                javaExe(),
                jar.toString(),
                List.of(),
                "##JKJC:",
                List.of("@/nonexistent/spec.txt"),
                protocol::add,
                passthrough::add);
        assertThat(code).isNotEqualTo(0);
    }
}
