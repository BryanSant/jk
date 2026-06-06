// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Profile;
import dev.jkbuild.model.Profiles;

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

    /** Which languages a project compiles. */
    public record Languages(boolean java, boolean kotlin) {}

    /**
     * Resolve which languages a project compiles. Explicit {@code jk.toml}
     * opt-ins win: {@code java = <int>} enables Java, {@code kotlin = "<ver>"}
     * enables Kotlin (either or both). When <em>neither</em> is declared, infer
     * from the tree — a {@code src/main/java} dir or any {@code .java} under
     * {@code src/} enables Java (at the jdk release); a {@code src/main/kotlin}
     * dir or any {@code .kt} enables Kotlin (at the default release). A project
     * with nothing to go on defaults to Java (a bare {@code jdk = N} project).
     */
    public static Languages resolveLanguages(JkBuild.Project project, Path projectDir) {
        boolean javaDeclared = project.java() > 0;
        boolean kotlinDeclared = project.isKotlin();
        if (javaDeclared || kotlinDeclared) {
            return new Languages(javaDeclared, kotlinDeclared);
        }
        Path src = projectDir.resolve("src");
        boolean java = Files.isDirectory(projectDir.resolve("src/main/java")) || anySourceUnder(src, ".java");
        boolean kotlin = Files.isDirectory(projectDir.resolve("src/main/kotlin")) || anySourceUnder(src, ".kt");
        if (!java && !kotlin) {
            return new Languages(true, false);   // nothing detected — default to Java
        }
        return new Languages(java, kotlin);
    }

    /** True if any regular file ending in {@code ext} exists anywhere under {@code root}. */
    private static boolean anySourceUnder(Path root, String ext) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(ext));
        } catch (IOException e) {
            return false;
        }
    }

    /** All {@code .java} files under {@code root} (empty if it doesn't exist). */
    public static List<Path> collectJavaSources(Path root) throws IOException {
        return collectFilesWithExtension(root, ".java");
    }

    /**
     * All {@code .kt} files for a project. Searches in order:
     * <ol>
     *   <li>{@code src/main/kotlin} — standard Maven/Gradle layout</li>
     *   <li>{@code src/main/java} — mixed layout (Maven users sometimes place .kt here)</li>
     *   <li>{@code src/} directly, excluding {@code src/test/} — compact/flat layout
     *       ({@code jk new --compact}) where {@code src/Main.kt} lives at the root of
     *       {@code src/} with no subdirectory nesting.</li>
     * </ol>
     */
    public static List<Path> collectKotlinSources(Path projectDir) throws IOException {
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/kotlin"), ".kt"));
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/java"), ".kt"));
        if (out.isEmpty()) {
            // Compact/flat layout: src/Main.kt alongside (but not under) src/test/.
            Path src = projectDir.resolve("src");
            Path srcTest = src.resolve("test");
            if (Files.isDirectory(src)) {
                try (Stream<Path> stream = Files.walk(src)) {
                    stream.filter(Files::isRegularFile)
                          .filter(p -> p.getFileName().toString().endsWith(".kt"))
                          .filter(p -> !p.startsWith(srcTest))
                          .forEach(out::add);
                }
            }
        }
        return out;
    }

    /** All {@code .kt} test files: under {@code src/test/kotlin} and {@code src/test/java}. */
    public static List<Path> collectKotlinTestSources(Path projectDir) throws IOException {
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/test/kotlin"), ".kt"));
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/test/java"), ".kt"));
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

    /**
     * Pick the active profile. Explicit {@code explicitName} wins; otherwise the
     * {@code ci} profile is auto-selected when running on CI. Returns null when
     * no profile applies.
     */
    public static Profile resolveProfile(Profiles profiles, String explicitName) {
        if (explicitName != null && !explicitName.isBlank()) {
            return profiles.resolve(explicitName);
        }
        String auto = Profiles.autoSelect(System.getenv());
        if (auto != null && profiles.contains(auto)) {
            return profiles.resolve(auto);
        }
        return null;
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
