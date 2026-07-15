// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile;

import build.jumpkick.jdk.HostPlatform;
import build.jumpkick.util.PathUtil;
import build.jumpkick.worker.JvmOptions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link JavaCompileStrategy}: execs {@code <java-home>/bin/javac} as a subprocess so the
 * project's pinned JDK drives compilation regardless of what JVM jk itself is running on.
 * Diagnostics are parsed from javac's stderr.
 *
 * <p>Why subprocess by default:
 *
 * <ul>
 *   <li>The native-image binary has no JVM and can't host {@code javax.tools.JavaCompiler}
 *       in-process.
 *   <li>Aligning the compiler with {@code project.jdk} is automatic — no "current JVM matches?"
 *       branch.
 *   <li>Per-call process isolation: a hung annotation processor doesn't take jk down.
 * </ul>
 *
 * <p>Long classpaths use an {@code @argfile} so we don't blow past {@code ARG_MAX}. javac's
 * diagnostic format ( {@code <file>:<line>: <severity>: <message>}) has been stable for two
 * decades; the parser is a single regex.
 */
public final class SubprocessJavacExtension implements JavaCompileStrategy {

    private static final Pattern DIAGNOSTIC =
            Pattern.compile("^(?<file>.+?):(?<line>\\d+): (?<sev>error|warning|note): (?<msg>.*)$");

    @Override
    public String name() {
        return "subprocess";
    }

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

        Path javaHome = request.javaHome() != null ? request.javaHome() : Path.of(System.getProperty("java.home"));
        Path javac = javaHome.resolve("bin").resolve(HostPlatform.isWindows() ? "javac.exe" : "javac");
        if (!Files.exists(javac)) {
            throw new IOException(
                    "javac not found at " + javac + " — project.jdk needs to point at a JDK (not a JRE).");
        }

        try {
            Path argfile = writeArgfile(request, outDir);
            try {
                // -J flags reach javac's own JVM launcher and are rejected inside an
                // @argfile (javac processes the file itself, after the JVM is already
                // up) — they must be direct command-line arguments.
                List<String> command = new ArrayList<>();
                command.add(javac.toString());
                command.addAll(JvmOptions.launcherFlags(1));
                // AOT cache for javac's own JVM (PluginAot): mapped when one exists for this
                // JDK + GC, else a background trainer is kicked off so the NEXT compile maps it.
                command.addAll(build.jumpkick.worker.PluginAot.javacFlags(javaHome));
                command.add("@" + argfile);
                ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
                Process process = pb.start();
                List<String> stray = new ArrayList<>();
                List<CompileResult.Diagnostic> diagnostics = parseStream(process, stray);
                int exit = process.waitFor();
                if (exit != 0 && !hasErrors(diagnostics)) {
                    // javac died without any per-source diagnostic (bad flag, unreadable
                    // classpath entry it didn't attribute, a crash, …). Surface whatever it
                    // printed as an ERROR — a failed compile must never be silent.
                    diagnostics = new ArrayList<>(diagnostics);
                    diagnostics.add(new CompileResult.Diagnostic(
                            CompileResult.Severity.ERROR,
                            null,
                            -1,
                            -1,
                            "javac exited with code " + exit
                                    + (stray.isEmpty() ? " and no diagnostics" : ":\n" + String.join("\n", stray))));
                }
                return new CompileResult(exit == 0 && !hasErrors(diagnostics), diagnostics);
            } finally {
                Files.deleteIfExists(argfile);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("javac was interrupted", e);
        } finally {
            if (scratch != null) PathUtil.deleteRecursively(scratch);
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
     * Header-less diagnostics javac emits without a {@code file:line:} anchor — fatal setup errors
     * ({@code error: error reading <jar>; zip END header not found}, {@code error: invalid flag}),
     * bare warnings ({@code warning: [options] …}), and trailing notes ({@code Note: … uses
     * unchecked or unsafe operations.}).
     */
    private static final Pattern BARE_DIAGNOSTIC =
            Pattern.compile("^(?<sev>error|warning|note): (?<msg>.*)$", Pattern.CASE_INSENSITIVE);

    /**
     * Group javac's output into one {@link CompileResult.Diagnostic} per diagnostic, keeping each
     * block <em>verbatim</em> — the {@code file:line: severity: message} header plus the source
     * snippet, caret, and any {@code symbol:}/{@code location:} trailer lines. A block runs from one
     * header line up to (but not including) the next header or the {@code "N errors"} summary. The
     * full text is the diagnostic's message; the CLI relativizes paths and colorizes on top.
     *
     * <p>Header-less {@code error:}/{@code warning:}/{@code note:} lines outside a block (fatal
     * setup errors like an unreadable classpath jar have no {@code file:line:} anchor) become
     * standalone diagnostics; {@code javac: …} launcher failures become standalone errors. Any
     * remaining unattributed lines (usage text, crash traces) are collected into {@code stray} so
     * the caller can surface them when javac fails without a parsed error.
     */
    private static List<CompileResult.Diagnostic> parseStream(Process process, List<String> stray)
            throws IOException {
        List<CompileResult.Diagnostic> diagnostics = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            CompileResult.Severity sev = null;
            Path file = null;
            long lineNo = -1;
            StringBuilder block = null;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = DIAGNOSTIC.matcher(line);
                Matcher bare;
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
                } else if ((bare = BARE_DIAGNOSTIC.matcher(line)).matches()) {
                    diagnostics.add(new CompileResult.Diagnostic(
                            parseSeverity(bare.group("sev")), null, -1, -1, line));
                } else if (line.startsWith("javac: ")) {
                    // Launcher-level failure (invalid flag, file not found, bad argfile).
                    diagnostics.add(
                            new CompileResult.Diagnostic(CompileResult.Severity.ERROR, null, -1, -1, line));
                } else if (SUMMARY.matcher(line).matches()) {
                    // Tally after a bare error ("1 error") — already accounted for.
                } else {
                    // Unattributed noise (usage text, crash trace) — kept for the caller.
                    stray.add(line);
                }
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

    // Allow ServiceLoader.load to instantiate this without a META-INF/services
    // file — JavaCompileStrategies falls back to a direct `new` when no impl
    // is registered. The ServiceLoader path is for plugins.
    @SuppressWarnings("unused")
    private static SubprocessJavacExtension create() {
        return new SubprocessJavacExtension();
    }
}
