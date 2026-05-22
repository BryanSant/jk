// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.lock.Lockfile;
import dev.buildjk.lock.LockfileWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code jk init} — initialize a new jk project in the current (or given) directory.
 *
 * <p>v0.1 scope: write a minimal {@code build.jk} and an empty {@code jk.lock}.
 * Refuses to overwrite existing files. Importing from an existing pom.xml or
 * build.gradle(.kts) lands at v0.5.
 */
@Command(name = "init", description = "Initialize a new jk project")
public final class InitCommand implements Callable<Integer> {

    @Option(names = "--lib", description = "Initialize as a library project (no application entrypoint).")
    boolean lib;

    @Option(names = "--bin", description = "Initialize as a binary project (default).")
    boolean bin;

    @Option(names = "--group", description = "Maven groupId. Default: 'com.example'.")
    String group = "com.example";

    @Option(names = "--name", description = "Project name (artifactId). Default: current directory name.")
    String name;

    @Option(names = "--jdk", description = "JDK version (SDKMAN candidate spec). Default: '25'.")
    String jdk = "25";

    @Parameters(arity = "0..1", description = "Target directory. Default: current directory.")
    Path directory;

    @Override
    public Integer call() throws IOException {
        Path target = directory != null ? directory : Path.of(".").toAbsolutePath().normalize();
        Files.createDirectories(target);

        if (lib && bin) {
            System.err.println("jk init: --lib and --bin are mutually exclusive");
            return 64; // EX_USAGE
        }
        boolean asLibrary = lib;

        String artifact = name != null ? name : target.getFileName().toString();
        if (artifact == null || artifact.isBlank() || artifact.equals(".")) {
            artifact = "app";
        }

        Path buildFile = target.resolve("build.jk");
        Path lockFile = target.resolve("jk.lock");

        if (Files.exists(buildFile)) {
            System.err.println("jk init: refusing to overwrite existing build.jk at " + buildFile);
            return 2; // EX_CONFIG
        }
        if (Files.exists(lockFile)) {
            System.err.println("jk init: refusing to overwrite existing jk.lock at " + lockFile);
            return 2;
        }

        Files.writeString(buildFile, renderBuildJk(group, artifact, jdk, asLibrary), StandardCharsets.UTF_8);
        LockfileWriter.write(Lockfile.empty(Jk.VERSION), lockFile);

        System.out.println("Created " + buildFile);
        System.out.println("Created " + lockFile);
        return 0;
    }

    static String renderBuildJk(String group, String artifact, String jdk, boolean asLibrary) {
        StringBuilder sb = new StringBuilder();
        sb.append("project {\n");
        sb.append("  group    = \"").append(group).append("\"\n");
        sb.append("  artifact = \"").append(artifact).append("\"\n");
        sb.append("  version  = \"0.1.0\"\n");
        sb.append("  jdk      = \"").append(jdk).append("\"\n");
        if (!asLibrary) {
            sb.append("  bin      = \"").append(artifact).append("\"\n");
        }
        sb.append("}\n");
        return sb.toString();
    }
}
