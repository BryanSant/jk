// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Default {@link KotlinCompileStrategy}: execs {@code <kotlin-home>/bin/kotlinc}
 * as a subprocess. Drops the ~50 MB {@code kotlin-compiler-embeddable}
 * dependency from jk's binary and lets {@code project.kotlin} drive the
 * compiler version directly.
 *
 * <p>{@code kotlinHome} resolution order, when not given on the request:
 * <ol>
 *   <li>{@code KOTLIN_HOME} environment variable.</li>
 *   <li>{@code ~/.jk/tools/kotlin/<version>/} — populated by jk's tool
 *       installer the first time {@code .kt} sources are seen.</li>
 * </ol>
 * Both can be absent: the strategy errors with a hint to install Kotlin.
 */
public final class SubprocessKotlincStrategy implements KotlinCompileStrategy {

    @Override
    public String name() { return "subprocess"; }

    @Override
    public KotlincResult compile(KotlincRequest request) throws IOException {
        Files.createDirectories(request.outputDir());

        Path kotlinHome = resolveKotlinHome(request);
        if (kotlinHome == null) {
            return new KotlincResult(false,
                    "kotlinc not found: set KOTLIN_HOME, install via ~/.jk/tools/kotlin/,"
                            + " or pass kotlinHome on the KotlincRequest.");
        }
        Path kotlinc = kotlinHome.resolve("bin")
                .resolve(isWindows() ? "kotlinc.bat" : "kotlinc");
        if (!Files.exists(kotlinc)) {
            return new KotlincResult(false,
                    "kotlinc binary missing at " + kotlinc
                            + " (kotlinHome=" + kotlinHome + ")");
        }

        List<String> command = new ArrayList<>();
        command.add(kotlinc.toString());
        command.add("-d");
        command.add(request.outputDir().toAbsolutePath().toString());
        command.add("-jvm-target");
        command.add(Integer.toString(request.jvmTarget()));
        if (!request.classpath().isEmpty()) {
            command.add("-classpath");
            StringBuilder cp = new StringBuilder();
            String sep = System.getProperty("path.separator");
            for (int i = 0; i < request.classpath().size(); i++) {
                if (i > 0) cp.append(sep);
                cp.append(request.classpath().get(i).toAbsolutePath());
            }
            command.add(cp.toString());
        }
        for (Path src : request.sources()) {
            command.add(src.toAbsolutePath().toString());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = pb.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = process.waitFor();
            return new KotlincResult(exit == 0, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("kotlinc was interrupted", e);
        }
    }

    private static Path resolveKotlinHome(KotlincRequest request) {
        if (request.kotlinHome() != null) return request.kotlinHome();
        String env = System.getenv("KOTLIN_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
