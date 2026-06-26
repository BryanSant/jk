// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.cli.run.ConsoleSpec;
import dev.jkbuild.cli.run.GoalConsole;
import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.CommandManager;
import dev.jkbuild.cli.tui.Glyphs;
import dev.jkbuild.cli.tui.GoalWedge;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.tool.ToolResolver;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.worker.JvmOptions;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jk format} — format the project's Java/Kotlin sources via the
 * {@code jk-formatter} worker (Spotless + optional OpenRewrite import optimisation).
 *
 * <p>On an interactive TTY (apply mode, non-quiet) the command runs a silent stealth
 * check first to count how many files need formatting, then renders a live
 * {@link CommandManager} progress view as files are processed. On a pipe / under
 * {@code --quiet} / in {@code --check} mode it falls back to plain println output.
 *
 * <p>Defaults to Palantir (Java) + ktfmt KOTLINLANG (Kotlin), both 4-space / 120-col.
 * {@code --java-style}/{@code --kotlin-style}/{@code --style} and the {@code [format]}
 * block override (see {@link FormatStyles}). {@code --check} verifies without writing
 * and exits non-zero if anything is unformatted.
 *
 * <p>The formatter implementation jars (palantir-java-format / google-java-format /
 * ktfmt) are resolved on demand through jk's own resolver into the CAS and handed to
 * the worker, so they never enter the main jk binary.
 */
public final class FormatCommand implements CliCommand {

    // jk-pinned formatter impl versions (resolved via jk; the worker uses these).
    private static final String PALANTIR_VERSION = "2.80.0";
    private static final String GOOGLE_VERSION = "1.28.0";
    private static final String KTFMT_VERSION = "0.61";
    private static final int KOTLIN_MAX_WIDTH = 120; // match Palantir's 120-col

    // OpenRewrite engine bundled in the formatter worker fat-JAR; no separate
    // runtime resolution needed — the version is fixed at worker-build time.
    private static final String REWRITE_VERSION = "8.56.1";

    // palantir/google-java-format reflectively use the JDK compiler internals.
    private static final List<String> JAVAC_EXPORTS = List.of(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");

    @Override
    public String name() {
        return "format";
    }

    @Override
    public String description() {
        return "Format Java/Kotlin source code";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Check formatting; fail if unformatted.", "--check"),
                Opt.value("<style>", "Java style: palantir | google | aosp.", "--java-style"),
                Opt.value("<style>", "Kotlin style: kotlinlang | google | meta.", "--kotlin-style"),
                Opt.value("<preset>", "Cross-language preset for both: standard.", "--style"),
                Opt.flag("Shorten FQCNs and add imports (default on).", "--optimize-imports"),
                Opt.flag("Skip FQCN-to-import optimization.", "--no-optimize-imports"),
                Opt.value("<file>", "OpenRewrite YAML config; overrides/extends recipes.", "--rewrite-config"));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        long startMs = System.currentTimeMillis();
        GlobalOptions global = GlobalOptions.from(in);
        boolean check = in.isSet("check");
        Path projectDir = global.workingDir();
        Path buildFile = projectDir.resolve("jk.toml");
        if (!Files.exists(buildFile)) {
            System.err.println("jk format: no jk.toml in " + PathDisplay.styledRaw(projectDir));
            return 2;
        }
        JkBuild build = JkBuildParser.parse(buildFile);

        // --optimize-imports / --no-optimize-imports / env var / jk.toml / default true
        Boolean cliOptimize = in.isSet("optimize-imports")
                ? Boolean.TRUE
                : in.isSet("no-optimize-imports") ? Boolean.FALSE : envBool("JK_FORMAT_OPTIMIZE_IMPORTS");
        // --rewrite-config / env var
        Path rewriteConfig = in.value("rewrite-config")
                .or(() -> java.util.Optional.ofNullable(System.getenv("JK_FORMAT_REWRITE_CONFIG")))
                .map(Path::of)
                .orElse(null);

        FormatStyles.Resolved styles;
        try {
            styles = FormatStyles.resolve(
                    in.value("java-style").orElse(null),
                    in.value("kotlin-style").orElse(null),
                    in.value("style").orElse(null),
                    cliOptimize,
                    build.format());
        } catch (IllegalArgumentException e) {
            System.err.println("jk format: " + e.getMessage());
            return 64;
        }
        // Supplying --rewrite-config implicitly enables optimize-imports when neither
        // flag nor env var said otherwise (so the OpenRewrite pipeline actually runs).
        boolean optimizeImports = styles.optimizeImports()
                || (rewriteConfig != null && cliOptimize == null && envBool("JK_FORMAT_OPTIMIZE_IMPORTS") == null);

        List<Path> javaFiles = collectSources(projectDir, ".java");
        List<Path> kotlinFiles = collectSources(projectDir, ".kt");
        if (javaFiles.isEmpty() && kotlinFiles.isEmpty()) {
            if (!global.outputIsJson()) System.out.println("jk format: no Java or Kotlin sources found.");
            return 0;
        }

        Path cache = JkDirs.cache();
        var cas = new Cas(cache);
        var resolver = ToolResolver.mavenCentral(new Http(), cas);

        List<Path> javaJars = javaFiles.isEmpty()
                ? List.of()
                : resolver.resolve(javaCoord(styles.java()), "java-format", "ignored")
                        .classpath();
        List<Path> kotlinJars = kotlinFiles.isEmpty()
                ? List.of()
                : resolver.resolve(Coordinate.of("com.facebook", "ktfmt", KTFMT_VERSION), "ktfmt", "ignored")
                        .classpath();

        // Build the worker command once — shared by the stealth check and the apply run.
        List<String> workerCmd = buildWorkerCmd(cas, !javaFiles.isEmpty());

        // Animated TUI: apply mode on an interactive TTY without --quiet/--json.
        boolean animate = !check && !global.outputIsJson() && !global.noProgress && GoalConsole.isInteractiveTerminal();

        if (!animate) {
            // Plain path: --check mode, piped output, CI, or --no-progress.
            Path spec = writeSpec(
                    check, styles, javaFiles, javaJars, kotlinFiles, kotlinJars, optimizeImports, rewriteConfig);
            try {
                return runPlain(workerCmd, spec, check, global, startMs, projectDir);
            } finally {
                Files.deleteIfExists(spec);
            }
        }

        // Animated path ──────────────────────────────────────────────────────
        // 1. Silent stealth check — count files that need formatting.
        Path checkSpec =
                writeSpec(true, styles, javaFiles, javaJars, kotlinFiles, kotlinJars, optimizeImports, rewriteConfig);
        int toFormat;
        try {
            toFormat = stealthCount(workerCmd, checkSpec, global);
        } finally {
            Files.deleteIfExists(checkSpec);
        }

        boolean nerdfont = dev.jkbuild.config.GlobalConfig.nerdfont();
        String took = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));

        if (toFormat == 0) {
            // Nothing to do — settle immediately without a second worker pass.
            System.out.println(GoalWedge.chipLine(
                    Glyphs.CHECK,
                    "Format",
                    nerdfont,
                    Theme.colorize("Already clean", Theme.active().success()) + " " + took));
            return 0;
        }

        // 2. Format with live TUI.
        Path applySpec =
                writeSpec(false, styles, javaFiles, javaJars, kotlinFiles, kotlinJars, optimizeImports, rewriteConfig);
        try {
            return runAnimated(workerCmd, applySpec, global, startMs, projectDir, toFormat, optimizeImports, nerdfont);
        } finally {
            Files.deleteIfExists(applySpec);
        }
    }

    // -------------------------------------------------------------------------
    // Worker command builder
    // -------------------------------------------------------------------------

    private List<String> buildWorkerCmd(Cas cas, boolean hasJava) throws IOException {
        Path workerJar = WorkerJar.FORMATTER.locate(cas);
        Path javaExe = CompileToolchain.runningJavaHome()
                .resolve("bin")
                .resolve(HostPlatform.isWindows() ? "java.exe" : "java");
        List<String> rest = new ArrayList<>();
        if (hasJava) rest.addAll(JAVAC_EXPORTS);
        rest.add("-jar");
        rest.add(workerJar.toString());
        return JvmOptions.javaCommand(javaExe.toString(), 1, rest);
    }

    // -------------------------------------------------------------------------
    // Plain (non-animated) execution — used for --check and non-TTY apply
    // -------------------------------------------------------------------------

    private int runPlain(
            List<String> baseCmd, Path spec, boolean check, GlobalOptions global, long startMs, Path projectDir)
            throws IOException, InterruptedException {
        List<String> cmd = withSpec(baseCmd, spec);
        int[] counts = {0, 0, 0}; // changed, clean, errors
        int exit = WorkerProcess.run(
                cmd,
                "##JKFMT:",
                json -> {
                    String t = Ndjson.str(json, "t");
                    if ("file".equals(t)) {
                        String status = Ndjson.str(json, "status");
                        String path = Ndjson.str(json, "path");
                        if ("changed".equals(status)) {
                            counts[0]++;
                            if (!global.outputIsJson()) {
                                String mark = Theme.colorize(
                                        Glyphs.CHECK, Theme.active().success());
                                String rel = Theme.colorize(
                                        PathDisplay.of(Path.of(path), projectDir),
                                        Theme.active().path());
                                System.out.println(mark + " " + (check ? "Would format: " : "Formatted: ") + rel);
                            }
                        } else if ("error".equals(status)) {
                            counts[2]++;
                            System.err.println("  error  " + path + ": " + Ndjson.str(json, "msg"));
                        } else {
                            counts[1]++;
                        }
                    }
                },
                line -> {
                    if (global.verbose) System.err.println("  [formatter] " + line);
                });

        if (!global.outputIsJson()) {
            String mark = Theme.colorize(Glyphs.CHECK, Theme.active().success());
            String verb = Theme.colorize(
                    check ? "Checked" : "Formatted", Theme.active().focused());
            String body = check
                    ? counts[0] + " to format, " + counts[1] + " already clean"
                    : counts[0] + " file" + (counts[0] == 1 ? "" : "s") + ", " + counts[1] + " already clean";
            if (counts[2] > 0) body += ", " + counts[2] + " error" + (counts[2] == 1 ? "" : "s");
            String inTime = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));
            System.out.println(mark + " " + verb + " " + body + " " + inTime);
        }
        return exit;
    }

    // -------------------------------------------------------------------------
    // Stealth check — silent pass to count files that need formatting
    // -------------------------------------------------------------------------

    private int stealthCount(List<String> baseCmd, Path checkSpec, GlobalOptions global)
            throws IOException, InterruptedException {
        List<String> cmd = withSpec(baseCmd, checkSpec);
        int[] changed = {0};
        WorkerProcess.run(
                cmd,
                "##JKFMT:",
                json -> {
                    if ("file".equals(Ndjson.str(json, "t")) && "changed".equals(Ndjson.str(json, "status"))) {
                        changed[0]++;
                    }
                },
                line -> {
                    if (global.verbose) System.err.println("  [formatter-check] " + line);
                });
        return changed[0];
    }

    // -------------------------------------------------------------------------
    // Animated TUI execution
    // -------------------------------------------------------------------------

    private int runAnimated(
            List<String> baseCmd,
            Path applySpec,
            GlobalOptions global,
            long startMs,
            Path projectDir,
            int toFormat,
            boolean optimizeImports,
            boolean nerdfont)
            throws IOException, InterruptedException {
        List<String> cmd = withSpec(baseCmd, applySpec);
        String subtitle = optimizeImports ? "Optimizing Imports & Applying Code Style" : "Applying Code Style";

        try (CommandManager cm = CommandManager.goal(System.out, "Format", true)) {
            cm.progress(0, toFormat);
            cm.addPhaseLabeled("", "fmt", subtitle);
            cm.phaseRunning("", "fmt");

            int[] counts = {0, 0, 0}; // changed, clean, errors
            int exit = WorkerProcess.run(
                    cmd,
                    "##JKFMT:",
                    json -> {
                        String t = Ndjson.str(json, "t");
                        if (!"file".equals(t)) return;
                        String status = Ndjson.str(json, "status");
                        String path = Ndjson.str(json, "path");
                        if ("changed".equals(status)) {
                            counts[0]++;
                            cm.progress(counts[0], toFormat);
                            cm.addCompletion(completionLine(counts[0], toFormat, path, projectDir));
                        } else if ("error".equals(status)) {
                            counts[2]++;
                            cm.writeAbove(
                                    Theme.colorize("  error", Theme.active().error()) + "  " + path + ": "
                                            + Ndjson.str(json, "msg"));
                        } else {
                            counts[1]++;
                        }
                    },
                    line -> {
                        if (global.verbose) cm.writeAbove("  [formatter] " + line);
                    });

            cm.phaseDone("", "fmt", counts[2] == 0);

            String took = ConsoleSpec.took(Duration.ofMillis(System.currentTimeMillis() - startMs));
            if (counts[2] > 0) {
                String errTail = counts[2] + " error" + (counts[2] == 1 ? "" : "s");
                cm.finishGoalFailure(errTail);
            } else {
                int pct = toFormat > 0 ? counts[0] * 100 / toFormat : 100;
                String tail =
                        Theme.colorize("Successfully formatted", Theme.active().success())
                                + " " + counts[0] + " of " + toFormat
                                + " files (" + pct + "%) " + took;
                cm.finishGoalSuccess(tail);
            }
            return exit;
        }
    }

    /** Format a single completion line: {@code ✓ [NNN of MMM] path/to/File.java}. */
    private static String completionLine(int num, int total, String absPath, Path projectDir) {
        int digits = String.valueOf(total).length();
        String numStr = String.format("%0" + digits + "d", num);
        Theme t = Theme.active();
        String counter = Theme.colorize("[" + numStr + " of " + total + "]", t.darkGray());
        String rel = Theme.colorize(PathDisplay.of(Path.of(absPath), projectDir), t.path());
        return Theme.colorize(Glyphs.CHECK, t.success()) + " " + counter + " " + rel;
    }

    /** Append the spec-file path argument to a base worker command. */
    private static List<String> withSpec(List<String> baseCmd, Path spec) {
        List<String> cmd = new ArrayList<>(baseCmd);
        cmd.add(spec.toAbsolutePath().toString());
        return cmd;
    }

    // -------------------------------------------------------------------------
    // Helpers shared by all execution paths
    // -------------------------------------------------------------------------

    /** Read an env var as a Boolean; returns null when absent or empty. */
    private static Boolean envBool(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return null;
        return Boolean.parseBoolean(v.trim());
    }

    private Coordinate javaCoord(String style) {
        return "palantir".equals(style)
                ? Coordinate.of("com.palantir.javaformat", "palantir-java-format", PALANTIR_VERSION)
                : Coordinate.of("com.google.googlejavaformat", "google-java-format", GOOGLE_VERSION);
    }

    private String javaVersion(String style) {
        return "palantir".equals(style) ? PALANTIR_VERSION : GOOGLE_VERSION;
    }

    private Path writeSpec(
            boolean check,
            FormatStyles.Resolved styles,
            List<Path> javaFiles,
            List<Path> javaJars,
            List<Path> kotlinFiles,
            List<Path> kotlinJars,
            boolean optimizeImports,
            Path rewriteConfig)
            throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("mode\t" + (check ? "check" : "apply"));
        if (!javaFiles.isEmpty()) {
            lines.add("java\t" + styles.java() + "\t" + javaVersion(styles.java()) + "\t" + joinJars(javaJars));
        }
        if (!kotlinFiles.isEmpty()) {
            lines.add("kotlin\t" + styles.kotlin() + "\t" + KTFMT_VERSION + "\t" + KOTLIN_MAX_WIDTH + "\t"
                    + joinJars(kotlinJars));
        }
        boolean anyRewrite = (optimizeImports || rewriteConfig != null) && !javaFiles.isEmpty();
        if (anyRewrite) {
            lines.add("rewrite-flags\toptimize-imports=" + optimizeImports);
            if (rewriteConfig != null) {
                lines.add("rewrite-config\t" + rewriteConfig.toAbsolutePath());
            }
        }
        for (Path f : javaFiles) lines.add("f\tjava\t" + f.toAbsolutePath());
        for (Path f : kotlinFiles) lines.add("f\tkotlin\t" + f.toAbsolutePath());
        Path spec = Files.createTempFile("jk-format-", ".spec");
        Files.write(spec, lines, StandardCharsets.UTF_8);
        return spec;
    }

    private static String joinJars(List<Path> jars) {
        return jars.stream()
                .map(p -> p.toAbsolutePath().toString())
                .reduce((a, b) -> a + File.pathSeparator + b)
                .orElse("");
    }

    /** Collect project source files with the given extension, skipping build/VCS output dirs. */
    private static List<Path> collectSources(Path root, String ext) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(ext))
                    .filter(FormatCommand::notExcluded)
                    .sorted()
                    .toList();
        }
    }

    private static boolean notExcluded(Path p) {
        for (Path seg : p) {
            String s = seg.toString();
            if (s.equals("target")
                    || s.equals("build")
                    || s.equals(".jk")
                    || s.equals(".git")
                    || s.equals("node_modules")) {
                return false;
            }
        }
        return true;
    }
}
