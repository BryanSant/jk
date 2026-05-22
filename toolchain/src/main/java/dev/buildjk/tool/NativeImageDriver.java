// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Thin driver over GraalVM's {@code native-image} binary (PRD §6
 * {@code jk native-image} verb). Verifies the JDK ships
 * {@code bin/native-image}, assembles the classpath argument, and execs
 * with inherited IO.
 *
 * <p>jk does <i>not</i> automatically install GraalVM here — the user
 * pins a GraalVM JDK via {@code project.jdk} or {@code .jk-version}.
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
        Path binary = nativeImageBinary(request.javaHome());
        if (!Files.exists(binary)) {
            throw new IOException("native-image binary not found at " + binary
                    + " — install a GraalVM JDK (e.g. `jk jdk install graalvm-25`)"
                    + " and pin it via project.jdk.");
        }
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
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    /** Resolve {@code <javaHome>/bin/native-image} for the current OS. */
    public static Path nativeImageBinary(Path javaHome) {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return javaHome.resolve("bin").resolve(win ? "native-image.cmd" : "native-image");
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
