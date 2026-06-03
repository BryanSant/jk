// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pure source-set helpers shared by the build pipeline and the git-source
 * builder. Extracted out of the CLI's {@code CompileCommand} so embedders can
 * drive compilation inputs without depending on {@code :cli}.
 */
public final class CompileSupport {

    private CompileSupport() {}

    /** All {@code .java} files under {@code root} (empty if it doesn't exist). */
    public static List<Path> collectJavaSources(Path root) throws IOException {
        return collectFilesWithExtension(root, ".java");
    }

    /**
     * All {@code .kt} files for a project: under {@code src/main/kotlin} and also
     * under {@code src/main/java} (a common shortcut Maven users take — kotlinc
     * handles both layouts).
     */
    public static List<Path> collectKotlinSources(Path projectDir) throws IOException {
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/kotlin"), ".kt"));
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/java"), ".kt"));
        return out;
    }

    /**
     * The {@code -jvm-target} kotlinc should use for a given Java release.
     * Kotlin tops out at 21 today; targeting a newer JDK is fine because Java is
     * bytecode-backward-compatible.
     */
    public static int kotlinJvmTarget(int release) {
        return Math.min(release, 21);
    }

    private static List<Path> collectFilesWithExtension(Path root, String extension) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(extension))
                    .forEach(result::add);
        }
        return result;
    }
}
