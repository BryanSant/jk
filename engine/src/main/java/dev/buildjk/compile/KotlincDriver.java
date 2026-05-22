// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import org.jetbrains.kotlin.cli.common.ExitCode;
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer;
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.jetbrains.kotlin.config.Services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Wraps {@code kotlin-compiler-embeddable}'s {@link K2JVMCompiler} so jk
 * can compile {@code .kt} sources in-process. Diagnostics are captured
 * via {@link PrintingMessageCollector} into a string so we can render
 * them through the same channel as javac messages.
 *
 * <p>v0.3 first iteration: pure-Kotlin compilation. Mixed Java/Kotlin
 * (where Kotlin code references Java that lives only in source form)
 * needs kotlinc to also drive javac in the same pass — that's a
 * future iteration.
 */
public final class KotlincDriver {

    public KotlincResult compile(KotlincRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            Files.createDirectories(request.outputDir());
        } catch (IOException e) {
            return new KotlincResult(false, "failed to create output dir: " + e.getMessage());
        }

        K2JVMCompilerArguments args = new K2JVMCompilerArguments();
        args.setFreeArgs(request.sources().stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.toList()));
        if (!request.classpath().isEmpty()) {
            // Setting --classpath turns OFF kotlinc's default classpath, which
            // includes kotlin-stdlib. Re-add stdlib (located via the
            // running JVM's classpath) so user code can reference kotlin.*.
            List<String> entries = new ArrayList<>();
            entries.add(stdlibJarPath());
            for (Path p : request.classpath()) {
                entries.add(p.toAbsolutePath().toString());
            }
            args.setClasspath(String.join(java.io.File.pathSeparator, entries));
        }
        args.setDestination(request.outputDir().toAbsolutePath().toString());
        args.setJvmTarget(Integer.toString(request.jvmTarget()));
        // Silence usage banners; jk reports its own messages.
        args.setNoStdlib(false);
        args.setNoReflect(true);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream messageStream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        MessageCollector collector = new PrintingMessageCollector(
                messageStream, MessageRenderer.WITHOUT_PATHS, true);

        ExitCode exit;
        try {
            exit = new K2JVMCompiler().exec(collector, Services.EMPTY, args);
        } catch (RuntimeException e) {
            return new KotlincResult(false, "kotlinc threw: " + e.getMessage());
        }
        String output = buffer.toString(StandardCharsets.UTF_8);
        return new KotlincResult(exit == ExitCode.OK, output);
    }

    /** Locate the kotlin-stdlib jar on jk's own classpath. */
    private static String stdlibJarPath() {
        try {
            return java.nio.file.Path.of(
                    kotlin.KotlinVersion.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException("kotlin-stdlib not on jk's classpath", e);
        }
    }

    /** Input to {@link KotlincDriver#compile}. */
    public record KotlincRequest(
            List<Path> sources,
            List<Path> classpath,
            Path outputDir,
            int jvmTarget) {

        public KotlincRequest {
            Objects.requireNonNull(sources, "sources");
            Objects.requireNonNull(classpath, "classpath");
            Objects.requireNonNull(outputDir, "outputDir");
            sources = List.copyOf(sources);
            classpath = List.copyOf(classpath);
            if (jvmTarget < 8) {
                throw new IllegalArgumentException("jvmTarget must be >= 8, got: " + jvmTarget);
            }
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private List<Path> sources = List.of();
            private List<Path> classpath = List.of();
            private Path outputDir;
            private int jvmTarget = 21;

            public Builder sources(List<Path> v) { this.sources = v; return this; }
            public Builder classpath(List<Path> v) { this.classpath = v; return this; }
            public Builder outputDir(Path v) { this.outputDir = v; return this; }
            public Builder jvmTarget(int v) { this.jvmTarget = v; return this; }

            public KotlincRequest build() {
                return new KotlincRequest(sources, classpath, outputDir, jvmTarget);
            }
        }
    }

    /** Outcome of a Kotlin compilation. {@code output} is whatever kotlinc printed. */
    public record KotlincResult(boolean success, String output) {

        public KotlincResult {
            Objects.requireNonNull(output, "output");
        }

        public List<String> errorLines() {
            List<String> errors = new ArrayList<>();
            for (String line : output.split("\n")) {
                if (line.startsWith("error:")) errors.add(line);
            }
            return errors;
        }
    }
}
