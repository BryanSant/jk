// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.compile;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Drives {@code javac} via {@link javax.tools.JavaCompiler}. Translates a
 * {@link CompileRequest} into the right options + file-manager setup,
 * captures diagnostics, and returns a {@link CompileResult}.
 *
 * <p>"Check mode" ({@link CompileRequest#isCheckOnly()}) writes class
 * files into a scratch directory that's deleted on completion — the
 * point is the diagnostics, not the bytes.
 */
public final class JavacDriver {

    public CompileResult compile(CompileRequest request) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "no system Java compiler available — jk requires a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Path scratch = null;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {

            Path outDir = request.outputDir();
            if (outDir == null) {
                scratch = Files.createTempDirectory("jk-check-");
                outDir = scratch;
            } else {
                Files.createDirectories(outDir);
            }
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(outDir));
            if (!request.classpath().isEmpty()) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH, request.classpath());
            }

            Iterable<? extends JavaFileObject> sources =
                    fm.getJavaFileObjectsFromPaths(request.sources());

            List<String> options = new ArrayList<>();
            options.add("--release");
            options.add(Integer.toString(request.release()));
            options.add("-encoding");
            options.add("UTF-8");
            options.addAll(request.extraOptions());

            boolean success;
            try {
                JavaCompiler.CompilationTask task = compiler.getTask(
                        null, fm, diagnostics, options, null, sources);
                success = task.call();
            } catch (RuntimeException e) {
                // javac surfaces fatal errors as RuntimeExceptions. Treat as failure.
                success = false;
            }

            return new CompileResult(success, convert(diagnostics));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (scratch != null) {
                deleteRecursively(scratch);
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    private static List<CompileResult.Diagnostic> convert(
            DiagnosticCollector<JavaFileObject> collector) {
        List<CompileResult.Diagnostic> result = new ArrayList<>(collector.getDiagnostics().size());
        for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            Path source = d.getSource() != null ? Path.of(d.getSource().toUri()) : null;
            long line = d.getLineNumber() == Diagnostic.NOPOS ? -1 : d.getLineNumber();
            long column = d.getColumnNumber() == Diagnostic.NOPOS ? -1 : d.getColumnNumber();
            result.add(new CompileResult.Diagnostic(
                    CompileResult.Severity.fromJavacKind(d.getKind()),
                    source,
                    line,
                    column,
                    d.getMessage(Locale.ROOT)));
        }
        return result;
    }

    private static void deleteRecursively(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {
            // Best-effort cleanup of a scratch dir.
        }
    }
}
