// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link JavaCompileStrategy}: execs {@code <java-home>/bin/javac}
 * as a subprocess so the project's pinned JDK drives compilation
 * regardless of what JVM jk itself is running on. Diagnostics are parsed
 * from javac's stderr.
 *
 * <p>Why subprocess by default:
 * <ul>
 *   <li>The native-image binary has no JVM and can't host
 *       {@code javax.tools.JavaCompiler} in-process.</li>
 *   <li>Aligning the compiler with {@code project.jdk} is automatic — no
 *       "current JVM matches?" branch.</li>
 *   <li>Per-call process isolation: a hung annotation processor doesn't
 *       take jk down.</li>
 * </ul>
 *
 * <p>Long classpaths use an {@code @argfile} so we don't blow past
 * {@code ARG_MAX}. javac's diagnostic format (
 * {@code <file>:<line>: <severity>: <message>}) has been stable for two
 * decades; the parser is a single regex.
 */
public final class SubprocessJavacStrategy implements JavaCompileStrategy {

    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^(?<file>.+?):(?<line>\\d+): (?<sev>error|warning|note): (?<msg>.*)$");

    @Override
    public String name() { return "subprocess"; }

    @Override
    public CompileResult compile(CompileRequest request) throws IOException {
        Path outDir = request.outputDir();
        Path scratch = null;
        if (outDir == null) {
            scratch = Files.createTempDirectory("jk-check-");
            outDir = scratch;
        } else {
            Files.createDirectories(outDir);
        }

        Path javaHome = request.javaHome() != null
                ? request.javaHome()
                : Path.of(System.getProperty("java.home"));
        Path javac = javaHome.resolve("bin").resolve(isWindows() ? "javac.exe" : "javac");
        if (!Files.exists(javac)) {
            throw new IOException("javac not found at " + javac
                    + " — project.jdk needs to point at a JDK (not a JRE).");
        }

        try {
            Path argfile = writeArgfile(request, outDir);
            try {
                ProcessBuilder pb = new ProcessBuilder(javac.toString(), "@" + argfile)
                        .redirectErrorStream(true);
                Process process = pb.start();
                List<CompileResult.Diagnostic> diagnostics = parseStream(process);
                int exit = process.waitFor();
                return new CompileResult(exit == 0 && !hasErrors(diagnostics), diagnostics);
            } finally {
                Files.deleteIfExists(argfile);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("javac was interrupted", e);
        } finally {
            if (scratch != null) deleteRecursively(scratch);
        }
    }

    private static Path writeArgfile(CompileRequest request, Path outDir) throws IOException {
        Path argfile = Files.createTempFile("jk-javac-", ".args");
        List<String> lines = new ArrayList<>();
        lines.add("-d");
        lines.add(quote(outDir.toAbsolutePath().toString()));
        lines.add("-encoding");
        lines.add("UTF-8");
        lines.add("--release");
        lines.add(Integer.toString(request.release()));
        if (!request.classpath().isEmpty()) {
            lines.add("-cp");
            StringBuilder cp = new StringBuilder();
            String sep = System.getProperty("path.separator");
            for (int i = 0; i < request.classpath().size(); i++) {
                if (i > 0) cp.append(sep);
                cp.append(request.classpath().get(i).toAbsolutePath());
            }
            lines.add(quote(cp.toString()));
        }
        if (!request.processorPath().isEmpty()) {
            // An explicit -processorpath both runs the processors and keeps them off
            // the compile classpath; modern javac won't auto-run classpath processors.
            lines.add("-processorpath");
            StringBuilder pp = new StringBuilder();
            String sep = System.getProperty("path.separator");
            for (int i = 0; i < request.processorPath().size(); i++) {
                if (i > 0) pp.append(sep);
                pp.append(request.processorPath().get(i).toAbsolutePath());
            }
            lines.add(quote(pp.toString()));
        }
        for (String opt : request.extraOptions()) {
            lines.add(opt);
        }
        for (Path src : request.sources()) {
            lines.add(quote(src.toAbsolutePath().toString()));
        }
        Files.writeString(argfile, String.join("\n", lines), StandardCharsets.UTF_8);
        return argfile;
    }

    /** Wrap in double quotes if the value contains chars javac's argfile parser is fussy about. */
    private static String quote(String value) {
        if (!needsQuoting(value)) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean needsQuoting(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '#' || c == '\'' || c == '"') {
                return true;
            }
        }
        return false;
    }

    /** {@code "<n> errors" / "<n> warnings"} — javac's trailing tally, not part of a block. */
    private static final Pattern SUMMARY = Pattern.compile("^\\d+ (?:error|warning)s?$");

    /**
     * Group javac's output into one {@link CompileResult.Diagnostic} per diagnostic,
     * keeping each block <em>verbatim</em> — the {@code file:line: severity: message}
     * header plus the source snippet, caret, and any {@code symbol:}/{@code location:}
     * trailer lines. A block runs from one header line up to (but not including) the
     * next header or the {@code "N errors"} summary. The full text is the diagnostic's
     * message; the CLI relativizes paths and colorizes on top.
     */
    private static List<CompileResult.Diagnostic> parseStream(Process process) throws IOException {
        List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            CompileResult.Severity sev = null;
            Path file = null;
            long lineNo = -1;
            StringBuilder block = null;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = DIAGNOSTIC.matcher(line);
                if (m.matches()) {
                    if (block != null) {
                        diagnostics.add(new CompileResult.Diagnostic(sev, file, lineNo, -1, block.toString()));
                    }
                    sev = parseSeverity(m.group("sev"));
                    file = Path.of(m.group("file"));
                    lineNo = Long.parseLong(m.group("line"));
                    block = new StringBuilder(line);
                } else if (block != null && SUMMARY.matcher(line).matches()) {
                    // Tally line ends the current block and the diagnostic stream's body.
                    diagnostics.add(new CompileResult.Diagnostic(sev, file, lineNo, -1, block.toString()));
                    block = null;
                } else if (block != null) {
                    // Snippet, caret, symbol:/location:, or wrapped message — keep verbatim.
                    block.append('\n').append(line);
                }
                // Lines before the first header (e.g. stray notes) are dropped.
            }
            if (block != null) {
                diagnostics.add(new CompileResult.Diagnostic(sev, file, lineNo, -1, block.toString()));
            }
        }
        return diagnostics;
    }

    private static CompileResult.Severity parseSeverity(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "error" -> CompileResult.Severity.ERROR;
            case "warning" -> CompileResult.Severity.WARNING;
            case "note" -> CompileResult.Severity.NOTE;
            default -> CompileResult.Severity.OTHER;
        };
    }

    private static boolean hasErrors(List<CompileResult.Diagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(d -> d.severity() == CompileResult.Severity.ERROR);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void deleteRecursively(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    // Allow ServiceLoader.load to instantiate this without a META-INF/services
    // file — JavaCompileStrategies falls back to a direct `new` when no impl
    // is registered. The ServiceLoader path is for plugins.
    @SuppressWarnings("unused")
    private static SubprocessJavacStrategy create() {
        return new SubprocessJavacStrategy();
    }
}
