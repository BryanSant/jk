// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compat.PassthroughEnv;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkResolver;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk mvn ...} — passthrough to Maven (PRD §24.1). The
 * {@code jk-compat-runner} worker provisions the distribution if needed and
 * returns its path; the main process then execs {@code bin/mvn} directly so
 * Maven's stdout/stderr reach the terminal unmodified.
 */
@Command(
        name = "mvn",
        description = "Passthrough to Maven (jk manages the install)",
        mixinStandardHelpOptions = false)
public final class MvnCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"})
    Path directory;

    @Option(names = "--tools-dir", hidden = true)
    Path toolsDir;

    @Option(names = "--jdks-dir", hidden = true)
    Path jdksDir;

    @Option(names = "--no-discover")
    boolean noDiscover;

    @Parameters(arity = "0..*", paramLabel = "<args>")
    List<String> args = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory.toAbsolutePath().normalize()
                : Path.of(".").toAbsolutePath().normalize();
        Path toolsRoot = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");
        Path cache = JkDirs.cache();

        // Provision Maven via the compat-runner, get back the bin path.
        Path mvnBin = provision(cache, projectDir, toolsRoot, noDiscover, false);
        if (mvnBin == null) return 1;

        // Exec Maven directly so stdio is inherited cleanly.
        Optional<InstalledJdk> jdk = JdkResolver.forProject(projectDir, jdksDir);
        List<String> command = new ArrayList<>();
        command.add(mvnBin.toString());
        command.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(command).directory(projectDir.toFile()).inheritIO();
        PassthroughEnv.apply(pb.environment(), jdk.map(InstalledJdk::home).orElse(null));
        return pb.start().waitFor();
    }

    static Path provision(Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean isGradle)
            throws IOException, InterruptedException {
        Path workerJar = CompatWorkerSetup.locateWorkerJar(new Cas(cache));
        Path spec = Files.createTempFile("jk-compat-", ".spec");
        try {
            Files.write(spec, List.of(
                    "COMMAND " + (isGradle ? "provision_gradle" : "provision_mvn"),
                    "PROJECT_DIR " + projectDir.toAbsolutePath(),
                    "TOOLS_ROOT " + toolsRoot.toAbsolutePath(),
                    "NO_DISCOVER " + noDiscover), StandardCharsets.UTF_8);

            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe.toString(), "-jar", workerJar.toString(),
                    spec.toAbsolutePath().toString()).redirectErrorStream(true);
            Process process = pb.start();
            String bin = null;
            StringBuilder diag = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = reader.readLine()) != null) {
                    if (!ln.startsWith("##JKCMP:")) { diag.append(ln).append('\n'); continue; }
                    String json = ln.substring("##JKCMP:".length());
                    if ("result".equals(readField(json, "t"))) {
                        bin = readField(json, "bin");
                        String err = readField(json, "error");
                        if (err != null) System.err.println("jk " + (isGradle ? "gradle" : "mvn") + ": " + err);
                        String src = readField(json, "source");
                        String ver = readField(json, "version");
                        if ("LINKED".equals(src) || "DOWNLOADED".equals(src)) {
                            System.err.println((isGradle ? "Gradle " : "Maven ") + ver + " " + src.toLowerCase());
                        }
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                if (diag.length() > 0) System.err.println(diag.toString().trim());
                return null;
            }
            return bin != null ? Path.of(bin) : null;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    static String readField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int s = json.indexOf(needle); if (s < 0) return null; s += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = s; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { char n = json.charAt(++i); if (n=='"') sb.append('"'); else { sb.append('\\'); sb.append(n); } }
            else if (c == '"') break; else sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isWindows() { return System.getProperty("os.name","").toLowerCase().contains("win"); }
}
