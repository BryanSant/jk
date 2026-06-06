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
 * <p>Everything lives under {@code target/}:
 * <ul>
 *   <li><b>Final artifacts</b> directly under {@code <workspaceRoot>/target/}
 *       — jars, native binaries, OCI tarballs. Shared across workspace members.</li>
 *   <li><b>Build intermediates</b> under {@code <memberRoot>/target/build/}
 *       — compiler outputs, generated sources, test reports. Per-member.</li>
 * </ul>
 *
 * <p>For a single-project (no {@code [workspace]} block), workspace root and
 * member root are the same directory, so everything sits under one {@code target/}.
 *
 * <p>Compiler output is split by toolchain to prevent the Kotlin incremental
 * compiler from pruning Java-compiled classes (it owns and prunes its directory):
 * <ul>
 *   <li>{@code target/build/kotlin/main} — kotlinc incremental workspace</li>
 *   <li>{@code target/build/classes/main} — javac output <em>and</em> the final
 *       assembled classes (kotlinc output is merged here after compilation)</li>
 * </ul>
 *
 * <p>This class is a pure value object — every method is pure, no directories
 * are created.
 */
public final class BuildLayout {

    private final Path workspaceRoot;
    private final Path memberRoot;
    private final String artifact;
    private final String version;

    private BuildLayout(Path workspaceRoot, Path memberRoot,
                        String artifact, String version) {
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.memberRoot    = Objects.requireNonNull(memberRoot, "memberRoot");
        this.artifact      = Objects.requireNonNull(artifact, "artifact");
        this.version       = Objects.requireNonNull(version, "version");
    }

    public static BuildLayout of(Path projectDir, JkBuild project) {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(project, "project");
        Path workspaceRoot = projectDir;
        if (!project.isWorkspaceRoot()) {
            try {
                workspaceRoot = WorkspaceLocator.findRoot(projectDir).orElse(projectDir);
            } catch (IOException ignored) {}
        }
        return new BuildLayout(workspaceRoot, projectDir,
                project.project().name(), project.project().version());
    }

    public static BuildLayout of(Path workspaceRoot, Path memberRoot, JkBuild project) {
        Objects.requireNonNull(project, "project");
        return new BuildLayout(workspaceRoot, memberRoot,
                project.project().name(), project.project().version());
    }

    // ---- Roots --------------------------------------------------------

    public Path workspaceRoot() { return workspaceRoot; }
    public Path memberRoot()    { return memberRoot; }
    public String artifact()    { return artifact; }
    public String version()     { return version; }

    // ---- Per-member build intermediates (under memberRoot/target/build/) ----

    /** {@code <memberRoot>/target/} — root of this member's output tree. */
    public Path memberTargetDir() {
        return memberRoot.resolve("target");
    }

    /** {@code <memberRoot>/target/build/} — root of all per-member build intermediates. */
    public Path buildDir() {
        return memberTargetDir().resolve("build");
    }

    /**
     * {@code target/build/classes/main/} — final assembled main classes.
     *
     * <p>Both javac output and (after assembly) kotlinc output land here.
     * This is the directory the JAR packager reads from, so it contains
     * all compiled classes regardless of which compiler produced them.
     * The Kotlin incremental compiler writes to {@link #kotlinClassesDir()}
     * first, then jk merges the result here.
     */
    public Path classesDir() {
        return buildDir().resolve("classes").resolve("main");
    }

    /** {@code target/build/classes/test/} — final assembled test classes. */
    public Path testClassesDir() {
        return buildDir().resolve("classes").resolve("test");
    }

    /**
     * {@code target/build/kotlin/main/} — kotlinc incremental workspace for main sources.
     *
     * <p>The Kotlin BTA incremental compiler owns this directory and prunes
     * any {@code .class} file it did not produce. It must never share a dir
     * with javac's output. After kotlinc finishes, jk merges the output into
     * {@link #classesDir()}.
     */
    public Path kotlinClassesDir() {
        return buildDir().resolve("kotlin").resolve("main");
    }

    /** {@code target/build/kotlin/test/} — kotlinc incremental workspace for test sources. */
    public Path kotlinTestClassesDir() {
        return buildDir().resolve("kotlin").resolve("test");
    }

    /** {@code target/build/resources/main/} — copied main resources. */
    public Path resourcesDir() {
        return buildDir().resolve("resources").resolve("main");
    }

    /** {@code target/build/resources/test/} — copied test resources. */
    public Path testResourcesDir() {
        return buildDir().resolve("resources").resolve("test");
    }

    /** {@code target/build/generated/sources/<processor>/main/} — annotation-processor output. */
    public Path generatedSourcesDir(String processor) {
        Objects.requireNonNull(processor, "processor");
        return buildDir().resolve("generated").resolve("sources")
                .resolve(processor).resolve("main");
    }

    /** {@code target/build/tmp/} — scratch space safe to delete between runs. */
    public Path tmpDir() {
        return buildDir().resolve("tmp");
    }

    /** {@code target/build/reports/} — test and coverage reports. */
    public Path reportsDir() {
        return buildDir().resolve("reports");
    }

    /** {@code target/build/reports/<member>/} — JUnit reports for a workspace member. */
    public Path testReportsDir(String member) {
        Objects.requireNonNull(member, "member");
        return reportsDir().resolve(member);
    }

    /** {@code target/build/test-results/} — JUnit XML test results. */
    public Path testResultsDir() {
        return buildDir().resolve("test-results");
    }

    // ---- Shared workspace artifacts (under workspaceRoot/target/) -----------

    /** {@code <workspaceRoot>/target/} — root of all final artifacts. */
    public Path targetDir() {
        return workspaceRoot.resolve("target");
    }

    /** {@code target/<artifact>-<version>.jar} — the main jar. */
    public Path mainJar() {
        return targetDir().resolve(artifact + "-" + version + ".jar");
    }

    /** {@code target/<artifact>-<version>-all.jar} — the shadow (fat) jar. */
    public Path shadowJar() {
        return targetDir().resolve(artifact + "-" + version + "-all.jar");
    }

    /** {@code target/<artifact>-<version>-sources.jar}. */
    public Path sourcesJar() {
        return targetDir().resolve(artifact + "-" + version + "-sources.jar");
    }

    /** {@code target/<artifact>-<version>-javadoc.jar}. */
    public Path javadocJar() {
        return targetDir().resolve(artifact + "-" + version + "-javadoc.jar");
    }

    /** {@code target/<artifact>} — GraalVM-compiled native binary. */
    public Path nativeBinary() {
        return targetDir().resolve(artifact);
    }

    /** {@code target/<artifact>.oci.tar} — default Jib OCI tarball. */
    public Path ociImageTar() {
        return targetDir().resolve(artifact + ".oci.tar");
    }

    /** {@code target/<artifact>-<version>-sbom/} — CycloneDX / SPDX outputs. */
    public Path sbomDir() {
        return targetDir().resolve(artifact + "-" + version + "-sbom");
    }

    /** {@code target/<artifact>-<version>-provenance/} — SLSA in-toto attestations. */
    public Path provenanceDir() {
        return targetDir().resolve(artifact + "-" + version + "-provenance");
    }
}
