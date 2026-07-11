// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a {@link StepSpec.Body} gets to work with, inside the plugin's worker JVM: the resolved
 * declared inputs, the scratch output root, progress labelling, and JDK tool forks — and nothing
 * about action keys, the CAS, or jk's directory layout.
 */
public interface StepExec {

    /** The module's compiled classes dir (resources copied in) — {@link In#classes()}. */
    Path classesDir();

    /** The resolved production RUNTIME classpath — {@link In#runtimeClasspath()}. */
    List<Path> runtimeClasspath();

    /** The plugin's validated config table. */
    dev.jkbuild.model.PluginConfig config();

    /** Read-only project facts (coords, resolved main, capability flags). */
    ProjectFacts project();

    /** The scratch root the step's declared output dirs resolve under. */
    Path scratch();

    /** Resolve (and create) a declared output dir under {@link #scratch()}. */
    default Path outputDir(String rel) throws IOException {
        return Files.createDirectories(scratch().resolve(rel));
    }

    /** The JDK this build runs on — for {@link #tool} forks. */
    Path javaHome();

    /** Progress label surfaced in the build UI. */
    void label(String text);

    /** A {@code bin/<name>} fork off {@link #javaHome()} ({@code java}, {@code javac}, …). */
    default ToolRun tool(String bin) {
        return new ToolRun(javaHome(), bin);
    }

    /** Convenience for the common case. */
    default ToolRun java() {
        return tool("java");
    }

    /** One JDK-tool subprocess: build args, run, get exit + combined output. */
    final class ToolRun {
        private final Path javaHome;
        private final String bin;
        private final List<String> args = new ArrayList<>();
        private Path cwd;

        private ToolRun(Path javaHome, String bin) {
            this.javaHome = javaHome;
            this.bin = bin;
        }

        public ToolRun classpath(List<Path> entries) {
            StringBuilder cp = new StringBuilder();
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) cp.append(System.getProperty("path.separator"));
                cp.append(entries.get(i).toAbsolutePath());
            }
            args.add("-cp");
            args.add(cp.toString());
            return this;
        }

        public ToolRun mainClass(String main) {
            args.add(main);
            return this;
        }

        public ToolRun args(List<String> more) {
            args.addAll(more);
            return this;
        }

        public ToolRun arg(String one) {
            args.add(one);
            return this;
        }

        public ToolRun cwd(Path dir) {
            this.cwd = dir;
            return this;
        }

        /** Fork and drain: exit code + combined stdout/stderr. */
        public Result run() throws IOException, InterruptedException {
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            List<String> command = new ArrayList<>();
            command.add(javaHome.resolve("bin").resolve(windows ? bin + ".exe" : bin).toString());
            command.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            if (cwd != null) pb.directory(cwd.toFile());
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            return new Result(process.waitFor(), output.toString());
        }

        public record Result(int exit, String output) {}
    }
}
