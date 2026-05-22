// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.hocon.BuildJkParser;
import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileReader;
import dev.buildjk.model.BuildJk;
import dev.buildjk.tool.NativeImageDriver;
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
        description = "Compile a native binary with GraalVM native-image.")
public final class NativeCommand implements Callable<Integer> {

    @Option(names = {"-C", "--directory"},
            description = "Project directory. Default: current directory.")
    Path directory;

    @Option(names = "--main",
            description = "Main class to compile. Default: read from build.jk's image.main-class.")
    String mainClass;

    @Option(names = "--output",
            description = "Output binary path. Default: target/<artifact>.")
    Path output;

    @Option(names = "--home", hidden = true,
            description = "Override the jk home root. Default: ~/.jk.")
    Path home;

    @Option(names = "--jdks-dir", hidden = true,
            description = "Override the JDK install root. Default: ~/.jk/jdks.")
    Path jdksDir;

    @Parameters(arity = "0..*", paramLabel = "<native-image-args>",
            description = "Extra arguments forwarded to native-image (after --).")
    List<String> extra = new ArrayList<>();

    @Override
    public Integer call() throws IOException, InterruptedException {
        Path projectDir = directory != null
                ? directory : Path.of(".").toAbsolutePath().normalize();
        Path buildJkPath = projectDir.resolve("build.jk");
        if (!Files.exists(buildJkPath)) {
            System.err.println("jk native: " + buildJkPath + " not found.");
            return 66;
        }
        BuildJk project = BuildJkParser.parse(buildJkPath);

        Path mainJar = projectDir.resolve("target").resolve(
                project.project().artifact() + "-" + project.project().version() + ".jar");
        if (!Files.exists(mainJar)) {
            System.err.println("jk native: main jar not found at " + mainJar
                    + " — run `jk build` first.");
            return 66;
        }

        Optional<InstalledJdk> jdk = EnvCommand.resolvePinnedJdk(projectDir, jdksDir);
        Path javaHome = jdk.map(InstalledJdk::home)
                .orElse(Path.of(System.getProperty("java.home")));

        String chosenMain = mainClass != null
                ? mainClass
                : dev.buildjk.hocon.ImageConfigParser.parse(buildJkPath).mainClass();
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
        Path jkHome = home != null
                ? home : Path.of(System.getProperty("user.home"), ".jk");
        Path casRoot = jkHome.resolve("cache").resolve("sha256");
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
