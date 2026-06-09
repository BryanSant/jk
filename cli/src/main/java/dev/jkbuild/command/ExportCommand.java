// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.worker.WorkerJar;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code jk export <file>} — emit a Maven-Central-grade {@code pom.xml} from
 * {@code jk.toml} via the {@code jk-compat-runner} worker (PRD §24.4).
 */
@Command(name = "export", description = "Emit a Maven pom.xml from jk.toml")
public final class ExportCommand implements Callable<Integer> {

    @Parameters(arity = "1", paramLabel = "<file>",
            description = "Target file: pom.xml (Gradle export is v1.1+).")
    Path target;

    @Option(names = "--force", description = "Overwrite an existing pom.xml.")
    boolean force;

    @picocli.CommandLine.Mixin GlobalOptions global;

    @Override
    public Integer call() throws IOException, InterruptedException {
        String filename = target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.equals("pom.xml")) {
            if (filename.startsWith("build.gradle")) {
                System.err.println("jk export: Gradle export is v1.1+ per PRD §24.4.");
            } else {
                System.err.println("jk export: expected `pom.xml` (got " + target.getFileName() + ").");
            }
            return 64;
        }

        Path projectDir = global.workingDir();
        if (!Files.exists(projectDir.resolve("jk.toml"))) {
            System.err.println("jk export: no jk.toml in " + projectDir);
            return 66;
        }

        Path effectiveTarget = target.isAbsolute() ? target : projectDir.resolve(target);
        if (Files.exists(effectiveTarget) && !force) {
            System.err.println("jk export: refusing to overwrite " + effectiveTarget + " (use --force).");
            return 73;
        }

        Path cache = JkDirs.cache();
        Path workerJar = WorkerJar.COMPAT_RUNNER.locate(new Cas(cache));
        Path spec = Files.createTempFile("jk-compat-", ".spec");
        try {
            Files.write(spec, List.of(
                    "COMMAND export",
                    "PROJECT_DIR " + projectDir.toAbsolutePath(),
                    "TARGET "      + effectiveTarget.toAbsolutePath(),
                    "FORCE "       + force), StandardCharsets.UTF_8);

            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe.toString(), "-jar", workerJar.toString(),
                    spec.toAbsolutePath().toString()).redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder diag = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = reader.readLine()) != null) {
                    if (!ln.startsWith("##JKCMP:")) { diag.append(ln).append('\n'); continue; }
                    String json = ln.substring("##JKCMP:".length());
                    String t = readField(json, "t");
                    if ("wrote".equals(t)) System.out.println("Wrote " + readField(json, "path"));
                    else if ("result".equals(t)) {
                        String err = readField(json, "error");
                        if (err != null) System.err.println("jk export: " + err);
                        String w = readNumericField(json, "warnings");
                        if (w != null && !w.equals("0"))
                            System.out.println("Export notes: " + w + " fidelity warning(s)");
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0 && diag.length() > 0) System.err.println("jk export: " + diag.toString().trim());
            return exit;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static String readField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int s = json.indexOf(needle); if (s < 0) return null; s += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = s; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { char n = json.charAt(++i); if (n == '"') sb.append('"'); else { sb.append('\\'); sb.append(n); } }
            else if (c == '"') break; else sb.append(c);
        }
        return sb.toString();
    }

    private static String readNumericField(String json, String key) {
        String needle = "\"" + key + "\":"; int s = json.indexOf(needle); if (s < 0) return null; s += needle.length();
        int e = s; while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        return e > s ? json.substring(s, e) : null;
    }

    private static boolean isWindows() { return System.getProperty("os.name","").toLowerCase().contains("win"); }
}
