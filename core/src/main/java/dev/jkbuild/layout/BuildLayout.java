// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.layout;

import dev.jkbuild.config.WorkspaceLocator;
import dev.jkbuild.model.JkBuild;

import java.io.IOException;
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
     * <p>If {@code projectDir} is itself a workspace root (its manifest
     * has a {@code [workspace]} table), the workspace root is
     * {@code projectDir} and intermediates + artifacts both land under it.
     *
     * <p>If {@code projectDir} is a workspace <em>member</em> (its parent
     * chain contains a workspace root whose {@code members} list names
     * this directory), the discovered workspace root hosts the
     * {@code target/} dir while intermediates stay under {@code projectDir}.
     * Same-target sharing across siblings means {@code jk build} from
     * inside a member writes its jar next to its siblings'.
     *
     * <p>Standalone projects (no enclosing workspace) fall back to
     * single-tier: workspace root == member root == project dir.
     *
     * <p>Workspace discovery silently falls back to single-tier on I/O
     * errors — better than failing a build over path resolution.
     */
    public static BuildLayout of(Path projectDir, JkBuild project) {
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(project, "project");
        Path workspaceRoot = projectDir;
        if (!project.isWorkspaceRoot()) {
            try {
                workspaceRoot = WorkspaceLocator.findRoot(projectDir).orElse(projectDir);
            } catch (IOException ignored) {
                // fall back to single-tier
            }
        }
        return new BuildLayout(
                workspaceRoot, projectDir,
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

    /**
     * {@code target/<artifact>.oci.tar} — default Jib OCI tarball.
     *
     * <p>Flat at the root of {@code target/} for the same reason as
     * {@link #nativeBinary()}: a single high-value artifact users grab to
     * push to a registry shouldn't be hidden under a subdir. Filename
     * extensions disambiguate from the native binary and the jar.
     * Per-platform variants (if we ever emit them) would land as
     * {@code <artifact>-linux-amd64.oci.tar} etc. at the same level.
     */
    public Path ociImageTar() {
        return targetDir().resolve(artifact + ".oci.tar");
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
