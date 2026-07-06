// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.cache.Cas;
import dev.jkbuild.cli.GlobalOptions;
import dev.jkbuild.cli.PathDisplay;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code jk import <file>} — convert a Maven or Gradle build to {@code jk.toml} via the {@code
 * jk-compat-runner} worker subprocess (PRD §24.2 / §24.3).
 */
public final class ImportCommand implements CliCommand {

    private static final List<String> AUTO_DETECT_ORDER = List.of("build.gradle.kts", "build.gradle", "pom.xml");

    @Override
    public String name() {
        return "import";
    }

    @Override
    public String description() {
        return "Convert a Maven or Gradle build to jk.toml";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<file>", "Path to write jk.toml.", "--out"),
                Opt.value("<file>", "Path to write the import report.", "--report"),
                Opt.flag("Overwrite existing jk.toml.", "--force"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of("file", Arity.ZERO_OR_ONE, "The build file to import (auto-detected if omitted)."));
    }

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        Path source =
                in.positionals().isEmpty() ? null : Path.of(in.positionals().get(0));
        Path out = in.value("out").map(Path::of).orElse(null);
        Path reportPath = in.value("report").map(Path::of).orElse(null);
        boolean force = in.isSet("force");
        GlobalOptions global = GlobalOptions.from(in);
        Path baseDir = global.workingDir();

        if (source == null) {
            source = autoDetectSource(baseDir);
            if (source == null) {
                CliOutput.err("jk import: no build file found in "
                        + baseDir
                        + " (looked for build.gradle.kts, build.gradle, pom.xml).");
                return Exit.NO_INPUT;
            }
            CliOutput.out("Importing " + PathDisplay.styled(source, baseDir));
        } else {
            source = source.isAbsolute() ? source : baseDir.resolve(source);
            if (!Files.exists(source)) {
                CliOutput.err("jk import: source not found: " + PathDisplay.styled(source, baseDir));
                return Exit.NO_INPUT;
            }
        }

        String filename = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!filename.endsWith("pom.xml") && !filename.equals("build.gradle") && !filename.equals("build.gradle.kts")) {
            CliOutput.err("jk import: expected pom.xml, build.gradle, or build.gradle.kts");
            return Exit.USAGE;
        }

        Path projectDir = source.toAbsolutePath().getParent();
        Path target = out != null ? out : projectDir.resolve("jk.toml");
        if (Files.exists(target) && !force) {
            CliOutput.err(
                    "jk import: refusing to overwrite " + PathDisplay.styled(target, baseDir) + " (use --force).");
            return Exit.CANT_CREATE;
        }

        Path cache = JkDirs.cache();
        List<String> lines = new ArrayList<>();
        lines.add("COMMAND import");
        lines.add("SOURCE " + source.toAbsolutePath());
        lines.add("OUT " + target.toAbsolutePath());
        lines.add("BASE_DIR " + projectDir.toAbsolutePath());
        lines.add("TMP_DIR " + JkDirs.tmp().toAbsolutePath());
        lines.add("FORCE " + force);
        if (reportPath != null) lines.add("REPORT " + reportPath.toAbsolutePath());

        return runWorker(cache, lines);
    }

    private int runWorker(Path cache, List<String> specLines) throws IOException, InterruptedException {
        Path workerJar = WorkerJar.COMPAT_BRIDGE.locate(new Cas(cache));
        Path spec = Files.createTempFile("jk-compat-", ".spec");
        try {
            Files.write(spec, specLines, StandardCharsets.UTF_8);
            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin")
                    .resolve(HostPlatform.isWindows() ? "java.exe" : "java");
            List<String> cmd = dev.jkbuild.worker.JvmOptions.javaCommand(
                    javaExe.toString(),
                    1,
                    List.of("-jar", workerJar.toString(), spec.toAbsolutePath().toString()));
            StringBuilder diag = new StringBuilder();
            int exit = new WorkerClient("##JKCMP:")
                    .on("wrote", json -> CliOutput.out("Wrote " + Ndjson.str(json, "path")))
                    .on("note", json -> CliOutput.out(Ndjson.str(json, "msg")))
                    .on("result", json -> {
                        String err = Ndjson.str(json, "error");
                        if (err != null) CliOutput.err("jk import: " + err);
                        int warnings = Ndjson.intValue(json, "warnings", 0);
                        if (warnings != 0) {
                            CliOutput.out("Import notes: " + warnings + " issue(s)");
                        }
                    })
                    .passthrough(ln -> diag.append(ln).append('\n'))
                    .run(cmd);
            if (exit != 0 && diag.length() > 0) {
                CliOutput.err("jk import: " + diag.toString().trim());
            }
            return exit;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    /**
     * Pick {@code <tmpDir>/<coord>-<n>-<sourceFile>-import.md}, incrementing {@code n} past any
     * existing file.
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
}
