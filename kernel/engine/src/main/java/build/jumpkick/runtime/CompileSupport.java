// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.model.JkBuild;
import build.jumpkick.model.Profile;
import build.jumpkick.model.Profiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Pure source-set helpers shared by the build pipeline and the git-source builder. Extracted out of
 * the CLI's {@code CompileCommand} so embedders can drive compilation inputs without depending on
 * {@code :cli}.
 */
public final class CompileSupport {

    private CompileSupport() {}

    /** Which languages a project compiles. */
    public record Languages(boolean java, boolean kotlin) {}

    /**
     * Resolve which languages a project compiles. Explicit {@code jk.toml} opt-ins win: {@code java =
     * <int>} enables Java, {@code kotlin = "<ver>"} enables Kotlin (either or both). When
     * <em>neither</em> is declared, infer from the tree — a {@code src/main/java} dir or any {@code
     * .java} under {@code src/} enables Java (at the jdk release); a {@code src/main/kotlin} dir or
     * any {@code .kt} enables Kotlin (at the default release). A project with nothing to go on
     * defaults to Java (a bare {@code jdk = N} project).
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
            return new Languages(true, false); // nothing detected — default to Java
        }
        return new Languages(java, kotlin);
    }

    /** True if {@code projectDir} contains any Java or Kotlin source files. */
    static boolean hasSources(Path projectDir) {
        return Files.isDirectory(projectDir.resolve("src/main/java"))
                || Files.isDirectory(projectDir.resolve("src/main/kotlin"))
                || anySourceUnder(projectDir.resolve("src"), ".java")
                || anySourceUnder(projectDir.resolve("src"), ".kt");
    }

    /** True if any regular file ending in {@code ext} exists anywhere under {@code root}. */
    private static boolean anySourceUnder(Path root, String ext) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.anyMatch(
                    p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(ext));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Whether this project uses the simple (flat) source layout.
     *
     * <p>Determined by {@code project.layout} in {@code jk.toml}:
     *
     * <ul>
     *   <li>{@code "simple"} — always use simple layout.
     *   <li>{@code "traditional"} — always use Maven layout.
     *   <li>{@code "auto"} or absent — infer from the directory tree: simple when both {@code
     *       src/main/kotlin} and {@code src/main/java} are absent or empty, traditional otherwise.
     * </ul>
     *
     * <p>Simple layout source roots:
     *
     * <ul>
     *   <li>Main: {@code ./src} (all {@code .kt} and {@code .java} files)
     *   <li>Test: {@code ./test} (all {@code .kt} and {@code .java} files)
     * </ul>
     */
    public static boolean isSimpleLayout(JkBuild.Project project, Path projectDir) {
        return build.jumpkick.layout.SourceLayout.isSimpleLayout(project, projectDir);
    }

    private static boolean anySourceUnder(Path root, String... extensions) {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.anyMatch(p -> {
                if (!Files.isRegularFile(p)) return false;
                String name = p.getFileName().toString();
                for (String ext : extensions) if (name.endsWith(ext)) return true;
                return false;
            });
        } catch (IOException e) {
            return false;
        }
    }

    /** All {@code .java} files under {@code root} (empty if it doesn't exist). */
    public static List<Path> collectJavaSources(Path root) throws IOException {
        return collectFilesWithExtension(root, ".java");
    }

    /**
     * All main {@code .kt} files for a project.
     *
     * <ul>
     *   <li>Standard layout: {@code src/main/kotlin/} and {@code src/main/java/}
     *   <li>Compact layout: {@code src/} (all {@code .kt} files)
     * </ul>
     */
    public static List<Path> collectKotlinSources(Path projectDir, boolean compact) throws IOException {
        if (compact) {
            return collectFilesWithExtension(projectDir.resolve("src"), ".kt");
        }
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/kotlin"), ".kt"));
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/main/java"), ".kt"));
        return out;
    }

    /**
     * All test {@code .kt} files for a project.
     *
     * <ul>
     *   <li>Standard layout: {@code src/test/kotlin/} and {@code src/test/java/}
     *   <li>Compact layout: {@code test/} (all {@code .kt} files)
     * </ul>
     */
    public static List<Path> collectKotlinTestSources(Path projectDir, boolean compact) throws IOException {
        if (compact) {
            return collectFilesWithExtension(projectDir.resolve("test"), ".kt");
        }
        List<Path> out = new ArrayList<>();
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/test/kotlin"), ".kt"));
        out.addAll(collectFilesWithExtension(projectDir.resolve("src/test/java"), ".kt"));
        return out;
    }

    /**
     * {@code [build] extra-src} roots resolved against the module dir, extant dirs only — the
     * core per-variant source-dir mechanism (variant overlays append to {@code extra-src};
     * {@code VariantApply} folds them before this runs). Missing dirs are fine: a variant that
     * declares {@code src/demo/kotlin} doesn't force the dir to exist.
     */
    public static List<Path> extraSrcDirs(build.jumpkick.model.JkBuild project, Path projectDir) {
        List<Path> out = new ArrayList<>();
        for (String rel : project.build().extraSrc()) {
            Path dir = projectDir.resolve(rel);
            if (Files.isDirectory(dir)) out.add(dir);
        }
        return out;
    }

    /** {@code base} plus every {@code extension} file under {@code extraDirs}, deduplicated. */
    public static List<Path> withExtraSources(List<Path> base, List<Path> extraDirs, String extension)
            throws IOException {
        if (extraDirs.isEmpty()) return base;
        var all = new java.util.LinkedHashSet<>(base);
        for (Path dir : extraDirs) all.addAll(collectFilesWithExtension(dir, extension));
        return new ArrayList<>(all);
    }

    /**
     * The {@code -jvm-target} kotlinc should use for a given Java release. Kotlin tops out at 21
     * today; targeting a newer JDK is fine because Java is bytecode-backward-compatible.
     */
    public static int kotlinJvmTarget(int release) {
        return Math.min(release, 21);
    }

    /**
     * Pick the active profile. Explicit {@code explicitName} wins; otherwise the {@code ci} profile
     * is auto-selected when running on CI. Returns null when no profile applies.
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
