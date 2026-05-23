// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.config.ImageConfigParser;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.tool.NativeImageDriver;
import dev.jkbuild.util.JkDirs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code jk native} — build a GraalVM-compiled native binary for the
 * project. Uses the project's pinned JDK (must be a GraalVM distribution);
 * classpath comes from {@code jk.lock} + the project's main jar.
 *
 * <p>Drives the upstream {@code native-image} tool from GraalVM; the verb
 * is named {@code native} (no {@code -image} suffix) to avoid colliding
 * with {@code jk image}, which builds an OCI container image.
 */
@Command(name = "native",
        description = "Compile a native binary with GraalVM native-image")
public final class NativeCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--main",
            description = "Main class to compile. Default: read from jk.toml's image.main-class.")
    String mainClass;

    @Option(names = "--output",
            description = "Output binary path. Default: target/<artifact>.")
    Path output;

    @Option(names = "--cache-dir", hidden = true,
            description = "Override the jk cache directory. Default: $JK_CACHE_DIR or ~/.cache/jk.")
    Path cacheDirOverride;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: the IntelliJ JDK directory.")
    Path jdksDir;

    @Parameters(arity = "0..*", paramLabel = "<native-image-args>",
            description = "Extra arguments forwarded to native-image (after --).")
    List<String> extra = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path jkBuildPath = projectDir.resolve("jk.toml");
        if (!Files.exists(jkBuildPath)) {
            System.err.println("jk native: " + jkBuildPath + " not found.");
            return 66;
        }
        JkBuild project = JkBuildParser.parse(jkBuildPath);

        Path mainJar = projectDir.resolve("target").resolve(
                project.project().artifact() + "-" + project.project().version() + ".jar");
        if (!Files.exists(mainJar)) {
            System.err.println("jk native: main jar not found at " + mainJar
                    + " — run `jk build` first.");
            return 66;
        }

        Optional<InstalledJdk> jdk = EnvCommand.resolvePinnedJdk(projectDir, jdksDir);
        Path javaHome = jdk.map(InstalledJdk::home).orElseGet(CompileToolchain::runningJavaHome);

        String chosenMain = mainClass != null
                ? mainClass
                : ImageConfigParser.parse(jkBuildPath).mainClass();
        if (chosenMain == null || chosenMain.isBlank()) {
            System.err.println("jk native: no main class — pass --main or set image.main-class.");
            return 64;
        }

        List<Path> classpath = new ArrayList<>();
        classpath.add(mainJar);
        classpath.addAll(loadLockedJars(projectDir));

        Path out = output != null
                ? output : projectDir.resolve("target").resolve(project.project().artifact());

        NativeImageDriver.Request request = new NativeImageDriver.Request(
                javaHome, classpath, chosenMain, out, extra);
        int exit = NativeImageDriver.run(request);
        if (exit == 0) {
            System.out.println("Built native binary " + out);
        }
        return exit;
    }

    private List<Path> loadLockedJars(Path projectDir) throws IOException {
        Path lockPath = projectDir.resolve("jk.lock");
        if (!Files.exists(lockPath)) return List.of();
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();
        Path casRoot = cache.resolve("sha256");
        Lockfile lock = LockfileReader.read(lockPath);
        List<Path> result = new ArrayList<>();
        for (Lockfile.Package pkg : lock.packages()) {
            if (pkg.checksum() == null) continue;
            String hex = pkg.checksum().startsWith("sha256:")
                    ? pkg.checksum().substring("sha256:".length())
                    : pkg.checksum();
            Path candidate = casRoot.resolve(hex.substring(0, 2)).resolve(hex);
            if (Files.exists(candidate)) result.add(candidate);
        }
        return result;
    }
}
