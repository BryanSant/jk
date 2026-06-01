// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JarPackager;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.publish.PublishablePom;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Builds a checked-out {@code jk.toml} project into a jar + POM headlessly —
 * the engine of git-source dependencies (docs/git-source-deps.md). Composes the
 * standalone build primitives (resolve → classpath → javac → jar → POM) without
 * the CLI goal/console machinery, so a git dependency can be built during
 * resolution.
 *
 * <p>v1 scope: Java sources + the project's own (Maven) dependencies. Kotlin,
 * test execution, native image, and signing are intentionally out — a git
 * dependency just needs its main artifact + POM. The published coordinate and
 * version are supplied by the caller (discovered from {@code [project]} or
 * overridden, and the git-derived version), and the rendered POM is stamped
 * with them so it matches the artifact path.
 */
final class GitProjectBuilder {

    private GitProjectBuilder() {}

    /** The built artifact: its coordinate/version plus the jar and POM bytes. */
    record Built(String group, String artifact, String version, byte[] jar, String pomXml) {
        String coordinate() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /**
     * Build {@code project} (rooted at {@code projectDir}) and produce the jar +
     * POM for coordinate {@code group:artifact:version}. Resolves the project's
     * own dependencies through {@code repos} (no network when it declares none);
     * compiles with {@code javaHome}.
     */
    static Built build(Path projectDir, JkBuild project,
                       String group, String artifact, String version,
                       Path javaHome, Cas cas, RepoGroup repos, String jkVersion)
            throws IOException, InterruptedException {

        // 1. Resolve the cloned project's own dependencies and the compile classpath.
        Lockfile lock = new LockOrchestrator(repos).lock(project, jkVersion);
        List<Path> classpath = new ClasspathResolver(cas).classpathFor(lock, ClasspathResolver.COMPILE_MAIN);

        // 2. Compile its Java sources.
        BuildLayout layout = BuildLayout.of(projectDir, project);
        Path classes = layout.classesDir();
        Files.createDirectories(classes);
        List<Path> sources = CompileCommand.collectJavaSources(projectDir.resolve("src/main/java"));
        if (!sources.isEmpty()) {
            CompileRequest request = CompileRequest.builder()
                    .sources(sources).classpath(classpath).outputDir(classes)
                    .release(project.project().javaRelease())
                    .extraOptions(List.of()).javaHome(javaHome)
                    .build();
            CompileResult result = new JavacDriver().compile(request);
            if (!result.success()) {
                String msgs = result.diagnostics().stream()
                        .map(CompileResult.Diagnostic::render).collect(Collectors.joining("; "));
                throw new IOException("git-source build failed for "
                        + group + ":" + artifact + ": " + msgs);
            }
        }
        copyTree(projectDir.resolve("src/main/resources"), classes);

        // 3. Package the jar.
        Path jarOut = layout.buildDir().resolve(artifact + "-" + version + ".jar");
        // Reproducible jar (epoch 0): the artifact is keyed by commit SHA, not mtime.
        new JarPackager().packageJar(new JarPackager.JarRequest(
                classes, jarOut, project.project().main(), 0L, Map.of()));
        byte[] jar = Files.readAllBytes(jarOut);

        // 4. Render the POM, stamped with the published coordinate + version.
        String pomXml = PublishablePom.render(
                withCoordinate(project, group, artifact, version),
                PublishablePom.Metadata.empty()).xml();

        return new Built(group, artifact, version, jar, pomXml);
    }

    /** A copy of {@code project} whose {@code [project]} coordinate/version are replaced. */
    private static JkBuild withCoordinate(JkBuild project, String group, String artifact, String version) {
        JkBuild.Project p = project.project();
        JkBuild.Project overridden = new JkBuild.Project(
                group, artifact, version, p.jdk(), p.java(), p.kotlin(), p.main(),
                p.shadow(), p.nativeMode(), p.description(), p.application(), p.m2install());
        return new JkBuild(overridden, project.dependencies(), project.repositories(),
                project.profiles(), project.features(), project.workspace(), project.manifest());
    }

    /** Copy a source tree into {@code dest} (for resources); no-op if absent. */
    private static void copyTree(Path src, Path dest) throws IOException {
        if (!Files.isDirectory(src)) return;
        try (var stream = Files.walk(src)) {
            for (Path p : stream.sorted(Comparator.naturalOrder()).toList()) {
                Path target = dest.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
