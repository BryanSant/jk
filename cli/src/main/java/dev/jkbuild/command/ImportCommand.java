// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.runtime.CompatWorkerSetup;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk import <file>} — convert a Maven or Gradle build to {@code jk.toml}
 * via the {@code jk-compat-runner} worker subprocess (PRD §24.2 / §24.3).
 */
@Command(name = "import", description = "Convert a Maven or Gradle build to jk.toml")
public final class ImportCommand implements Callable<Integer> {

    private static final List<String> AUTO_DETECT_ORDER =
            List.of("build.gradle.kts", "build.gradle", "pom.xml");

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Parameters(arity = "0..1", paramLabel = "file",
            description = "The build file to import (auto-detected if omitted).")
    Path source;

    @Option(names = "--out", description = "Path to write jk.toml.")
    Path out;

    @Option(names = "--report", description = "Path to write the import report.")
    Path reportPath;

    @Option(names = "--force", description = "Overwrite existing jk.toml.")
    boolean force;

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path baseDir = global.workingDir();

        if (source == null) {
            source = autoDetectSource(baseDir);
            if (source == null) {
                System.err.println("jk import: no build file found in " + baseDir
                        + " (looked for build.gradle.kts, build.gradle, pom.xml).");
                return 66;
            }
            System.out.println("Importing " + baseDir.relativize(source));
        } else {
            source = source.isAbsolute() ? source : baseDir.resolve(source);
            if (!Files.exists(source)) {
                System.err.println("jk import: source not found: " + source);
                return 66;
            }
        }

        String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith("pom.xml") && !filename.equals("build.gradle")
                && !filename.equals("build.gradle.kts")) {
            System.err.println("jk import: expected pom.xml, build.gradle, or build.gradle.kts");
            return 64;
        }

        Path projectDir = source.toAbsolutePath().getParent();
        Path target = out != null ? out : projectDir.resolve("jk.toml");
        if (Files.exists(target) && !force) {
            System.err.println("jk import: refusing to overwrite " + target + " (use --force).");
            return 73;
        }

        Path cache = JkDirs.cache();
        List<String> lines = new ArrayList<>();
        lines.add("COMMAND import");
        lines.add("SOURCE "   + source.toAbsolutePath());
        lines.add("OUT "      + target.toAbsolutePath());
        lines.add("BASE_DIR " + projectDir.toAbsolutePath());
        lines.add("TMP_DIR "  + JkDirs.tmp().toAbsolutePath());
        lines.add("FORCE "    + force);
        if (reportPath != null) lines.add("REPORT " + reportPath.toAbsolutePath());

        return runWorker(cache, lines);
    }

    private int runWorker(Path cache, List<String> specLines)
            throws IOException, InterruptedException {
        Path workerJar = CompatWorkerSetup.locateWorkerJar(new Cas(cache));
        Path spec = Files.createTempFile("jk-compat-", ".spec");
        try {
            Files.write(spec, specLines, StandardCharsets.UTF_8);
            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe.toString(), "-jar", workerJar.toString(),
                    spec.toAbsolutePath().toString()).redirectErrorStream(true);
            Process process = pb.start();
            int exit = 0;
            StringBuilder diag = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = reader.readLine()) != null) {
                    if (!ln.startsWith("##JKCMP:")) { diag.append(ln).append('\n'); continue; }
                    String json = ln.substring("##JKCMP:".length());
                    String t = readField(json, "t");
                    if ("wrote".equals(t)) System.out.println("Wrote " + readField(json, "path"));
                    else if ("note".equals(t)) System.out.println(readField(json, "msg"));
                    else if ("result".equals(t)) {
                        String err = readField(json, "error");
                        if (err != null) { System.err.println("jk import: " + err); }
                        String warnings = readNumericField(json, "warnings");
                        if (warnings != null && !warnings.equals("0")) {
                            System.out.println("Import notes: " + warnings + " issue(s)");
                        }
                    }
                }
            }
            exit = process.waitFor();
            if (exit != 0 && diag.length() > 0) {
                System.err.println("jk import: " + diag.toString().trim());
            }
            return exit;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /**
     * Pick {@code <tmpDir>/<coord>-<n>-<sourceFile>-import.md}, incrementing
     * {@code n} past any existing file.
     */
    static Path defaultReportPath(Path tmpDir, String coord, String sourceFileName) {
        for (int n = 1; ; n++) {
            Path candidate = tmpDir.resolve(coord + "-" + n + "-" + sourceFileName + "-import.md");
            if (!Files.exists(candidate)) return candidate;
        }
    }

    private static Path autoDetectSource(Path dir) {
        for (String name : AUTO_DETECT_ORDER) {
            Path c = dir.resolve(name);
            if (Files.isRegularFile(c)) return c;
        }
        return null;
    }

    private static String readField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return null;
        start += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                if (n == '"') sb.append('"'); else { sb.append('\\'); sb.append(n); }
            } else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String readNumericField(String json, String key) {
        String needle = "\"" + key + "\":";
        int s = json.indexOf(needle);
        if (s < 0) return null;
        s += needle.length();
        int e = s;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        return e > s ? json.substring(s, e) : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
