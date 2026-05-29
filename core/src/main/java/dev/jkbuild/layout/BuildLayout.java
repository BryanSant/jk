// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.layout;

import dev.jkbuild.model.JkBuild;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Centralized path decisions for jk's two-tier output layout.
 *
 * <p>The layout splits build artifacts into two locations:
 *
 * <ul>
 *   <li><b>Per-member intermediates</b> under {@code <memberRoot>/build/}
 *       — compiler outputs (.class files), copied resources, generated
 *       sources, and scratch dirs. Cheap to recreate from sources.</li>
 *   <li><b>Per-workspace final artifacts</b> under
 *       {@code <workspaceRoot>/target/} — the jars, native binaries,
 *       OCI tarballs, test reports, SBOMs, and provenance docs that the
 *       user consumes downstream. Shared across siblings in a workspace
 *       so {@code jk publish} / {@code jk image} have one place to look.</li>
 * </ul>
 *
 * <p>For a single-project (no {@code [workspace]} block) the
 * {@code workspaceRoot} and {@code memberRoot} coincide at the project
 * directory, so {@code build/} and {@code target/} sit side by side
 * under the project root.
 *
 * <p>Profiles deliberately do <b>not</b> partition the output. Java
 * bytecode is bytecode; debug-vs-release distinctions don't apply to a
 * JVM build the way they do for Cargo's native compilers. Profiles
 * drive build-time flags (javac args, JVM args) but the produced
 * filenames are identical regardless.
 *
 * <p>This class is a value object — every method is pure. No
 * directories are created; callers do that on demand via
 * {@code Files.createDirectories(layout.classesDir())}.
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

    /**
     * Build a layout for {@code projectDir} using the manifest's
     * {@code [project]} coordinates.
     *
     * <p>The {@code workspaceRoot} currently defaults to {@code projectDir}.
     * The workspace-aware path (where {@code workspaceRoot} is discovered
     * by walking up to the enclosing workspace member) will be wired in
     * once {@code jk build -p <member>} ships. Until then a workspace
     * member built standalone behaves like a single-project build —
     * intermediates and artifacts both root at the member dir.
     */
    public static BuildLayout of(Path projectDir, JkBuild project) {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(project, "project");
        return new BuildLayout(
                projectDir, projectDir,
                project.project().artifact(),
                project.project().version());
    }

    /**
     * Build a layout with explicit workspace and member roots. Reserved
     * for the workspace-aware execution surface; not currently invoked
     * by any command.
     */
    public static BuildLayout of(Path workspaceRoot, Path memberRoot, JkBuild project) {
        Objects.requireNonNull(project, "project");
        return new BuildLayout(
                workspaceRoot, memberRoot,
                project.project().artifact(),
                project.project().version());
    }

    // ---- Roots --------------------------------------------------------

    public Path workspaceRoot() { return workspaceRoot; }
    public Path memberRoot()    { return memberRoot; }
    public String artifact()    { return artifact; }
    public String version()     { return version; }

    // ---- Per-member intermediates -------------------------------------

    /** {@code <memberRoot>/build/} — root of all per-member intermediates. */
    public Path buildDir() {
        return memberRoot.resolve("build");
    }

    /** {@code build/classes/main/} — compiled main {@code .class} files (Java + Kotlin). */
    public Path classesDir() {
        return buildDir().resolve("classes").resolve("main");
    }

    /** {@code build/classes/test/} — compiled test {@code .class} files. */
    public Path testClassesDir() {
        return buildDir().resolve("classes").resolve("test");
    }

    /** {@code build/resources/main/} — copied main resources. */
    public Path resourcesDir() {
        return buildDir().resolve("resources").resolve("main");
    }

    /** {@code build/resources/test/} — copied test resources. */
    public Path testResourcesDir() {
        return buildDir().resolve("resources").resolve("test");
    }

    /** {@code build/generated/sources/<processor>/main/} — annotation-processor output. */
    public Path generatedSourcesDir(String processor) {
        Objects.requireNonNull(processor, "processor");
        return buildDir().resolve("generated").resolve("sources")
                .resolve(processor).resolve("main");
    }

    /** {@code build/tmp/} — scratch space safe to delete between runs. */
    public Path tmpDir() {
        return buildDir().resolve("tmp");
    }

    // ---- Shared root artifacts ----------------------------------------

    /** {@code <workspaceRoot>/target/} — root of all final artifacts. */
    public Path targetDir() {
        return workspaceRoot.resolve("target");
    }

    /** {@code target/<artifact>-<version>.jar} — the main jar. */
    public Path mainJar() {
        return targetDir().resolve(artifact + "-" + version + ".jar");
    }

    /** {@code target/<artifact>-<version>-sources.jar}. */
    public Path sourcesJar() {
        return targetDir().resolve(artifact + "-" + version + "-sources.jar");
    }

    /** {@code target/<artifact>-<version>-javadoc.jar}. */
    public Path javadocJar() {
        return targetDir().resolve(artifact + "-" + version + "-javadoc.jar");
    }

    /**
     * {@code target/<artifact>} — GraalVM-compiled native binary.
     *
     * <p>Sits flat at the root of {@code target/} (not under a
     * {@code native/} subdir) so the most common artifact a user wants to
     * find is in the most obvious place. No filename collision with
     * {@link #mainJar()} because the jar carries the
     * {@code -<version>.jar} suffix.
     */
    public Path nativeBinary() {
        return targetDir().resolve(artifact);
    }

    /** {@code target/images/<artifact>.oci.tar} — default Jib OCI tarball. */
    public Path ociImageTar() {
        return targetDir().resolve("images").resolve(artifact + ".oci.tar");
    }

    /** {@code target/reports/<member>/} — JUnit reports for a workspace member. */
    public Path testReportsDir(String member) {
        Objects.requireNonNull(member, "member");
        return targetDir().resolve("reports").resolve(member);
    }

    /** {@code target/sbom/} — CycloneDX / SPDX outputs. */
    public Path sbomDir() {
        return targetDir().resolve("sbom");
    }

    /** {@code target/provenance/} — SLSA in-toto attestations. */
    public Path provenanceDir() {
        return targetDir().resolve("provenance");
    }
}
