// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Thin driver over GraalVM's {@code native-image} binary (PRD §6
 * {@code jk native} verb). Verifies the JDK ships
 * {@code bin/native-image}, assembles the classpath argument, and execs.
 *
 * <p>Subprocess stdout and stderr are forwarded line-by-line through
 * {@code System.out} and {@code System.err} respectively. When the jk TUI
 * is active those streams have been replaced by {@code CommandManager}'s
 * {@code LineSink}, so all native-image output appears above the progress
 * bar rather than interleaved with it.
 *
 * <p>jk does <i>not</i> automatically install GraalVM here — the user
 * pins a GraalVM JDK via {@code project.jdk} or {@code .jdk-version}.
 * If {@code native-image} is missing the driver fails with a clear hint
 * to {@code jk jdk install graalvm-25}.
 */
public final class NativeImageDriver {

    public record Request(
            Path javaHome,
            List<Path> classpath,
            String mainClass,
            Path outputPath,
            List<String> extraArgs) {

        public Request {
            Objects.requireNonNull(javaHome, "javaHome");
            Objects.requireNonNull(mainClass, "mainClass");
            Objects.requireNonNull(outputPath, "outputPath");
            classpath = List.copyOf(classpath);
            extraArgs = List.copyOf(extraArgs);
        }
    }

    private NativeImageDriver() {}

    /** Exec native-image; return its exit code. */
    public static int run(Request request) throws IOException, InterruptedException {
        Path binary = resolve(request.javaHome())
                .orElseThrow(() -> notFoundError(request.javaHome()));
        List<String> command = new ArrayList<>();
        command.add(binary.toString());
        command.add("-cp");
        command.add(joinClasspath(request.classpath()));
        command.add("-o");
        command.add(request.outputPath().toAbsolutePath().toString());
        command.add("--no-fallback");
        command.addAll(request.extraArgs());
        command.add(request.mainClass());

        Files.createDirectories(request.outputPath().toAbsolutePath().getParent());

        // Do NOT use inheritIO() — that writes directly to fd 1/2, bypassing
        // System.out/System.err which CommandManager.captureOutput() replaces
        // with a LineSink to render output above the TUI progress bar.
        // Instead, pipe both streams and forward line-by-line through the
        // current System.out/System.err so the TUI always stays at the bottom.
        Process process = new ProcessBuilder(command).start();
        Thread fwdOut = forwardStream(process.getInputStream(), System.out);
        Thread fwdErr = forwardStream(process.getErrorStream(), System.err);
        int exit = process.waitFor();
        fwdOut.join();
        fwdErr.join();
        return exit;
    }

    private static Thread forwardStream(java.io.InputStream in,
                                        java.io.PrintStream dest) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    dest.println(line);
                }
            } catch (IOException ignored) {}
        }, "native-image-io");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Locate the {@code native-image} binary, trying in order:
     * <ol>
     *   <li>{@code <javaHome>/bin/native-image} — the project-pinned JDK</li>
     *   <li>{@code $GRAALVM_HOME/bin/native-image} — explicit GraalVM override</li>
     *   <li>{@code native-image} on {@code $PATH}</li>
     * </ol>
     * Returns the first candidate that exists as a regular file.
     */
    public static Optional<Path> resolve(Path javaHome) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = win ? "native-image.cmd" : "native-image";

        // 1. Project-pinned JDK
        if (javaHome != null) {
            Path p = javaHome.resolve("bin").resolve(exe);
            if (Files.isRegularFile(p)) return Optional.of(p);
        }

        // 2. $GRAALVM_HOME
        String graalHome = System.getenv("GRAALVM_HOME");
        if (graalHome != null && !graalHome.isBlank()) {
            Path p = Path.of(graalHome).resolve("bin").resolve(exe);
            if (Files.isRegularFile(p)) return Optional.of(p);
        }

        // 3. $PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String sep = System.getProperty("path.separator", ":");
            for (String dir : pathEnv.split(sep, -1)) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir).resolve(exe);
                if (Files.isRegularFile(p)) return Optional.of(p);
            }
        }

        return Optional.empty();
    }

    /** Resolve {@code <javaHome>/bin/native-image} for the current OS (no fallback). */
    public static Path nativeImageBinary(Path javaHome) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return javaHome.resolve("bin").resolve(win ? "native-image.cmd" : "native-image");
    }

    public static IOException notFoundError(Path javaHome) {
        return new IOException(
                "native-image binary not found.\n"
                + "  Checked: " + (javaHome != null ? javaHome.resolve("bin/native-image") + ", " : "")
                + "$GRAALVM_HOME/bin/native-image, PATH\n"
                + "  Install a GraalVM JDK and pin it:\n"
                + "    jk jdk install graalvm-25\n"
                + "    (or set $GRAALVM_HOME to your GraalVM installation)");
    }

    private static String joinClasspath(List<Path> classpath) {
        String sep = System.getProperty("path.separator");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < classpath.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(classpath.get(i).toAbsolutePath());
        }
        return sb.toString();
    }
}
