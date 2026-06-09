// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
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
            List<String> cmd = List.of(javaExe.toString(), "-jar",
                    workerJar.toString(), spec.toAbsolutePath().toString());
            StringBuilder diag = new StringBuilder();
            int exit = WorkerProcess.run(cmd, "##JKCMP:", json -> {
                String t = Ndjson.str(json, "t");
                if ("wrote".equals(t)) System.out.println("Wrote " + Ndjson.str(json, "path"));
                else if ("result".equals(t)) {
                    String err = Ndjson.str(json, "error");
                    if (err != null) System.err.println("jk export: " + err);
                    int w = Ndjson.intValue(json, "warnings", 0);
                    if (w != 0) System.out.println("Export notes: " + w + " fidelity warning(s)");
                }
            }, ln -> diag.append(ln).append('\n'));
            if (exit != 0 && diag.length() > 0) System.err.println("jk export: " + diag.toString().trim());
            return exit;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static boolean isWindows() { return System.getProperty("os.name","").toLowerCase().contains("win"); }
}
