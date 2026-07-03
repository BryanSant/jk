// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.layout;

import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Centralized path decisions for jk's build output layout.
 *
 * <p>Everything lives directly under {@code <moduleRoot>/target/}:
 *
 * <ul>
 *   <li><b>Build intermediates</b> — {@code classes/}, {@code kotlin/}, {@code resources/}, {@code
 *       generated/}, {@code tmp/}
 *   <li><b>Test results</b> — JUnit XML files under {@code reports/test-results/}; the
 *       human-readable {@code reports/test-results.md} alongside them
 *   <li><b>Final artifacts</b> — under {@code target/} for applications (project.main set), under {@code target/lib/} for libraries
 * </ul>
 *
 * <p>For a single-project (no {@code [workspace]} block), workspace root and module root are the
 * same directory, so everything sits under one {@code target/}.
 *
 * <p>Compiler output is split by toolchain to prevent the Kotlin incremental compiler from pruning
 * Java-compiled classes (it owns and prunes its directory):
 *
 * <ul>
 *   <li>{@code target/kotlin/main} — kotlinc incremental workspace
 *   <li>{@code target/classes/main} — javac output <em>and</em> the final assembled classes
 *       (kotlinc output is merged here after compilation)
 * </ul>
 *
 * <p>This class is a pure value object — every method is pure, no directories are created.
 */
public final class BuildLayout {

    private final Path workspaceRoot;
    private final Path moduleRoot;
    private final String artifact;
    private final String version;
    /** True when the project declares a {@code project.main} class (i.e. it is an application). */
    private final boolean hasMain;

    private BuildLayout(
            Path workspaceRoot, Path moduleRoot, String artifact, String version, boolean hasMain) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.moduleRoot = Objects.requireNonNull(moduleRoot, "moduleRoot");
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.version = Objects.requireNonNull(version, "version");
        this.hasMain = hasMain;
    }

    public static BuildLayout of(Path projectDir, JkBuild project) {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(project, "project");
        Path workspaceRoot = projectDir;
        if (!project.isWorkspaceRoot()) {
            try {
                workspaceRoot = WorkspaceLocator.findRoot(projectDir).orElse(projectDir);
            } catch (IOException ignored) {
            }
        }
        return new BuildLayout(
                workspaceRoot,
                projectDir,
                project.project().name(),
                project.project().version(),
                hasMain(project));
    }

    public static BuildLayout of(Path workspaceRoot, Path moduleRoot, JkBuild project) {
        Objects.requireNonNull(project, "project");
        return new BuildLayout(
                workspaceRoot,
                moduleRoot,
                project.project().name(),
                project.project().version(),
                hasMain(project));
    }

    private static boolean hasMain(JkBuild project) {
        String main = project.project().main();
        return main != null && !main.isBlank();
    }

    // ---- Roots --------------------------------------------------------

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path moduleRoot() {
        return moduleRoot;
    }

    public String artifact() {
        return artifact;
    }

    public String version() {
        return version;
    }

    /** True when this project declares {@code project.main} — i.e. it is an application. */
    public boolean hasMain() {
        return hasMain;
    }

    // ---- Per-module output (under moduleRoot/target/) ----------------------

    /** {@code <moduleRoot>/target/} — root of this module's output tree. */
    public Path moduleTargetDir() {
        return moduleRoot.resolve("target");
    }

    /**
     * {@code target/} — root of all per-module build intermediates.
     *
     * <p>Previously {@code target/build/}; all outputs now sit directly under {@code target/}
     * alongside the final artifacts.
     */
    public Path buildDir() {
        return moduleTargetDir();
    }

    /**
     * {@code target/classes/main/} — final assembled main classes.
     *
     * <p>Both javac output and (after assembly) kotlinc output land here. This is the directory the
     * JAR packager reads from, so it contains all compiled classes regardless of which compiler
     * produced them. The Kotlin incremental compiler writes to {@link #kotlinClassesDir()} first,
     * then jk merges the result here.
     */
    public Path classesDir() {
        return buildDir().resolve("classes").resolve("main");
    }

    /** {@code target/classes/test/} — final assembled test classes. */
    public Path testClassesDir() {
        return buildDir().resolve("classes").resolve("test");
    }

    /**
     * {@code target/jdt/classes/main/} — main class output for an external IDE language server
     * (Eclipse JDT-LS, used by VS Code's redhat.java). Kept separate from {@link #classesDir()} so an
     * IDE's continuous autobuild never collides with jk's incremental compiler, which deletes and
     * re-hashes every {@code .class} under its own output dir.
     */
    public Path jdtClassesDir() {
        return buildDir().resolve("jdt").resolve("classes").resolve("main");
    }

    /** {@code target/jdt/classes/test/} — test class output for an external IDE language server. */
    public Path jdtTestClassesDir() {
        return buildDir().resolve("jdt").resolve("classes").resolve("test");
    }

    /**
     * {@code target/kotlin/main/} — kotlinc incremental workspace for main sources.
     *
     * <p>The Kotlin BTA incremental compiler owns this directory and prunes any {@code .class} file
     * it did not produce. It must never share a dir with javac's output. After kotlinc finishes, jk
     * merges the output into {@link #classesDir()}.
     */
    public Path kotlinClassesDir() {
        return buildDir().resolve("kotlin").resolve("main");
    }

    /** {@code target/kotlin/test/} — kotlinc incremental workspace for test sources. */
    public Path kotlinTestClassesDir() {
        return buildDir().resolve("kotlin").resolve("test");
    }

    /** {@code target/resources/main/} — copied main resources. */
    public Path resourcesDir() {
        return buildDir().resolve("resources").resolve("main");
    }

    /** {@code target/resources/test/} — copied test resources. */
    public Path testResourcesDir() {
        return buildDir().resolve("resources").resolve("test");
    }

    /** {@code target/generated/sources/<processor>/main/} — annotation-processor output. */
    public Path generatedSourcesDir(String processor) {
        return generatedSourcesDir(processor, "main");
    }

    /**
     * {@code target/generated/sources/<processor>/<sourceSet>/} — annotation-processor output for a
     * given source set ({@code main} or {@code test}). Test processing must not share a directory
     * with main, or the two would clobber each other's generated files.
     */
    public Path generatedSourcesDir(String processor, String sourceSet) {
        Objects.requireNonNull(processor, "processor");
        Objects.requireNonNull(sourceSet, "sourceSet");
        return buildDir()
                .resolve("generated")
                .resolve("sources")
                .resolve(processor)
                .resolve(sourceSet);
    }

    /** {@code target/tmp/} — scratch space safe to delete between runs. */
    public Path tmpDir() {
        return buildDir().resolve("tmp");
    }

    /** {@code target/reports/} — test and coverage reports. */
    public Path reportsDir() {
        return buildDir().resolve("reports");
    }

    /** {@code target/reports/<module>/} — JUnit reports for a workspace module. */
    public Path testReportsDir(String module) {
        Objects.requireNonNull(module, "module");
        return reportsDir().resolve(module);
    }

    /** {@code target/reports/test-results/} — JUnit XML test results. */
    public Path testResultsDir() {
        return reportsDir().resolve("test-results");
    }

    /**
     * {@code target/reports/test-results.md} — human-readable markdown test results, written
     * alongside the XML files in {@code target/reports/}.
     */
    public Path markdownTestResults() {
        return reportsDir().resolve("test-results.md");
    }

    // ---- Final artifacts -------------------------------------------------------

    /**
     * {@code <moduleRoot>/target/} — root of all build output for this module.
     *
     * <p>Each project owns its own {@code target/} directory. The workspace root only gets a {@code
     * target/} if it has its own source code to build.
     */
    public Path targetDir() {
        return moduleRoot.resolve("target");
    }

    /**
     * Destination directory for deliverable artifacts (jars, binaries, OCI images).
     *
     * <ul>
     *   <li>{@code target/} when the project declares {@code project.main} — it is an application
     *       and its packaged output is a directly-runnable artifact.
     *   <li>{@code target/lib/} when no {@code project.main} is declared — it is a library whose
     *       packaged output is consumed by other projects, not run directly.
     * </ul>
     *
     * <p>This rule also applies to native shared-library outputs ({@code .so}, {@code .dylib},
     * {@code .dll}) produced by GraalVM {@code native-image --shared}.
     */
    public Path artifactDir() {
        return hasMain ? targetDir() : targetDir().resolve("lib");
    }

    /** {@code <artifactDir>/<artifact>-<version>.jar} — the main jar. */
    public Path mainJar() {
        return artifactDir().resolve(artifact + "-" + version + ".jar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-all.jar} — the shadow (fat) jar. */
    public Path shadowJar() {
        return artifactDir().resolve(artifact + "-" + version + "-all.jar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-sources.jar}. */
    public Path sourcesJar() {
        return artifactDir().resolve(artifact + "-" + version + "-sources.jar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-javadoc.jar}. */
    public Path javadocJar() {
        return artifactDir().resolve(artifact + "-" + version + "-javadoc.jar");
    }

    /** {@code <artifactDir>/<artifact>} — GraalVM-compiled native executable. */
    public Path nativeBinary() {
        return artifactDir().resolve(artifact);
    }

    /**
     * {@code <artifactDir>/lib<artifact>} — base path for a GraalVM-compiled native shared library
     * ({@code native-image --shared}). This is the {@code -o} basename only; native-image appends
     * the platform extension ({@code .so}/{@code .dylib}/{@code .dll}) and emits C headers alongside.
     */
    public Path nativeLibrary() {
        return artifactDir().resolve("lib" + artifact);
    }

    /** {@code <artifactDir>/<artifact>.oci.tar} — default Jib OCI tarball. */
    public Path ociImageTar() {
        return artifactDir().resolve(artifact + ".oci.tar");
    }

    /** {@code <artifactDir>/<artifact>-<version>-sbom/} — CycloneDX / SPDX outputs. */
    public Path sbomDir() {
        return artifactDir().resolve(artifact + "-" + version + "-sbom");
    }

    /** {@code <artifactDir>/<artifact>-<version>-provenance/} — SLSA in-toto attestations. */
    public Path provenanceDir() {
        return artifactDir().resolve(artifact + "-" + version + "-provenance");
    }
}
