// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compat.PassthroughEnv;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkResolver;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;
import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;
import dev.jkbuild.runtime.CompileToolchain;
import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code jk mvn ...} — passthrough to Maven (PRD §24.1). The
 * {@code jk-compat-runner} worker provisions the distribution if needed and
 * returns its path; the main process then execs {@code bin/mvn} directly so
 * Maven's stdout/stderr reach the terminal unmodified.
 */
public final class MvnCommand implements CliCommand {

    @Override public String name() { return "mvn"; }
    @Override public String description() { return "Passthrough to Maven (jk manages the install)"; }
    @Override public boolean passthrough() { return true; }
    @Override public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Project directory.", "-C", "--directory"),
                Opt.value("<dir>", "Override the tools install root.", "--tools-dir").hide(),
                Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir").hide(),
                Opt.flag("Skip tool discovery.", "--no-discover"));
    }
    @Override public List<Param> parameters() {
        return List.of(Param.of("args", Arity.ZERO_OR_MORE, "Arguments forwarded to Maven."));
    }

    Path directory;
    Path toolsDir;
    Path jdksDir;
    boolean noDiscover;
    List<String> args = new ArrayList<>();

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        this.directory = in.value("directory").map(Path::of).orElse(null);
        this.toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.noDiscover = in.isSet("no-discover");
        this.args = in.positionals();

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
        Path workerJar = WorkerJar.COMPAT_BRIDGE.locate(new Cas(cache));
        Path spec = Files.createTempFile("jk-compat-", ".spec");
        try {
            Files.write(spec, List.of(
                    "COMMAND " + (isGradle ? "provision_gradle" : "provision_mvn"),
                    "PROJECT_DIR " + projectDir.toAbsolutePath(),
                    "TOOLS_ROOT " + toolsRoot.toAbsolutePath(),
                    "NO_DISCOVER " + noDiscover), StandardCharsets.UTF_8);

            Path javaExe = CompileToolchain.runningJavaHome()
                    .resolve("bin").resolve(isWindows() ? "java.exe" : "java");
            List<String> cmd = dev.jkbuild.worker.JvmOptions.javaCommand(javaExe.toString(), 1,
                    List.of("-jar", workerJar.toString(), spec.toAbsolutePath().toString()));
            String[] bin = {null};
            StringBuilder diag = new StringBuilder();
            int exit = WorkerProcess.run(cmd, "##JKCMP:", json -> {
                if ("result".equals(Ndjson.str(json, "t"))) {
                    bin[0] = Ndjson.str(json, "bin");
                    String err = Ndjson.str(json, "error");
                    if (err != null) System.err.println("jk " + (isGradle ? "gradle" : "mvn") + ": " + err);
                    String src = Ndjson.str(json, "source");
                    String ver = Ndjson.str(json, "version");
                    if ("LINKED".equals(src) || "DOWNLOADED".equals(src)) {
                        System.err.println((isGradle ? "Gradle " : "Maven ") + ver + " " + src.toLowerCase());
                    }
                }
            }, ln -> diag.append(ln).append('\n'));
            if (exit != 0) {
                if (diag.length() > 0) System.err.println(diag.toString().trim());
                return null;
            }
            return bin[0] != null ? Path.of(bin[0]) : null;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    private static boolean isWindows() { return HostPlatform.isWindows(); }
}
