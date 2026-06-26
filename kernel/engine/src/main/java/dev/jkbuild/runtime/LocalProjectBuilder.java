// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.CompileResult;
import dev.jkbuild.compile.JarPackager;
import dev.jkbuild.compile.JavacDriver;
import dev.jkbuild.compile.KotlincDriver;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.compile.KotlincResult;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.publish.PublishablePom;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.LockOrchestrator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a {@code jk.toml} project on disk into a jar + POM headlessly — composing the standalone
 * build primitives (resolve → classpath → kotlinc → javac → merge → jar → POM) without the CLI
 * goal/console machinery. The engine of immutable git-source dependencies (via {@link
 * GitProjectBuilder} / {@link GitSourceMaterializer}). Composite {@code path}/branch-git deps now
 * build through the full pipeline ({@code BuildPipeline.coreBuilder}) instead.
 *
 * <p>Scope: Java + Kotlin sources, layout-aware (jk's flat {@code SIMPLE} layout and Maven-style
 * {@code TRADITIONAL}), plus the project's own (Maven) dependencies. Test execution, native image,
 * and signing are out — a source dependency only needs its main artifact + POM. The published
 * coordinate and version are supplied by the caller; the rendered POM is stamped to match.
 */
public final class LocalProjectBuilder {

    private LocalProjectBuilder() {}

    /** The built artifact: its coordinate/version plus the jar and POM bytes. */
    record Built(String group, String artifact, String version, byte[] jar, String pomXml) {
        String coordinate() {
            return group + ":" + artifact + ":" + version;
        }
    }

    /**
     * Build {@code project} (rooted at {@code projectDir}) into the jar + POM for coordinate {@code
     * group:artifact:version}. Resolves the project's own dependencies through {@code repos} (no
     * network when it declares none) and compiles with {@code javaHome}. The jar is written to {@link
     * BuildLayout#mainJar()} so callers can reuse it on disk.
     */
    static Built build(
            Path projectDir,
            JkBuild project,
            String group,
            String artifact,
            String version,
            Path javaHome,
            Cas cas,
            RepoGroup repos,
            String jkVersion)
            throws IOException, InterruptedException {

        // 1. Resolve the project's own dependencies and the compile classpath.
        Lockfile lock = new LockOrchestrator(repos).lock(project, jkVersion);
        List<Path> classpath =
                new ArrayList<>(new ClasspathResolver(cas).classpathFor(lock, ClasspathResolver.COMPILE_MAIN));

        BuildLayout layout = BuildLayout.of(projectDir, project);
        Path classes = layout.classesDir();
        Files.createDirectories(classes);

        boolean simple = CompileSupport.isSimpleLayout(project.project(), projectDir);
        CompileSupport.Languages langs = CompileSupport.resolveLanguages(project.project(), projectDir);
        Path javaRoot = simple ? projectDir.resolve("src") : projectDir.resolve("src/main/java");

        // 2a. Kotlin first — a mixed module's Kotlin reads Java *declarations*
        //     from source; the Kotlin compiler never emits Java bytecode.
        List<Path> javacCp = new ArrayList<>(classpath);
        if (langs.kotlin()) {
            List<Path> ktSources = CompileSupport.collectKotlinSources(projectDir, simple);
            if (!ktSources.isEmpty()) {
                Path ktOut = layout.kotlinClassesDir();
                Files.createDirectories(ktOut);
                KotlinWorkerSetup.Prepared kt =
                        KotlinWorkerSetup.prepare(repos, cas, CompileToolchain.kotlinVersionFor(lock, project));
                List<Path> ktCp = new ArrayList<>(classpath);
                ktCp.add(kt.stdlib()); // -no-stdlib: pair the version-matched stdlib
                List<String> ktArgs = new ArrayList<>();
                ktArgs.add("-no-stdlib");
                if (langs.java() && Files.isDirectory(javaRoot)) {
                    ktArgs.add("-Xjava-source-roots=" + javaRoot.toAbsolutePath());
                }
                KotlincRequest req = KotlincRequest.builder()
                        .sources(ktSources)
                        .classpath(ktCp)
                        .outputDir(ktOut)
                        .jvmTarget(
                                CompileSupport.kotlinJvmTarget(project.project().javaRelease()))
                        .workerClasspath(kt.workerClasspath())
                        .javaHome(javaHome)
                        .workingDir(layout.buildDir().resolve("kotlin-work"))
                        .extraArgs(ktArgs)
                        .build();
                KotlincResult r = new KotlincDriver().compile(req);
                if (!r.success()) {
                    throw new IOException("kotlin build failed for " + group + ":" + artifact + ": " + r.output());
                }
                javacCp.add(ktOut); // javac resolves Kotlin declarations from output
            }
        }

        // 2b. Java.
        if (langs.java()) {
            List<Path> sources = CompileSupport.collectJavaSources(javaRoot);
            if (!sources.isEmpty()) {
                CompileRequest request = CompileRequest.builder()
                        .sources(sources)
                        .classpath(javacCp)
                        .outputDir(classes)
                        .release(project.project().javaRelease())
                        .extraOptions(List.of())
                        .javaHome(javaHome)
                        .build();
                CompileResult result = new JavacDriver().compile(request);
                if (!result.success()) {
                    String msgs = result.diagnostics().stream()
                            .map(CompileResult.Diagnostic::render)
                            .collect(Collectors.joining("; "));
                    throw new IOException("java build failed for " + group + ":" + artifact + ": " + msgs);
                }
            }
        }

        // 2c. Merge Kotlin output into the assembled classes dir, then resources.
        copyTree(layout.kotlinClassesDir(), classes);
        copyTree(simple ? projectDir.resolve("resources") : projectDir.resolve("src/main/resources"), classes);

        // 3. Package the jar (epoch 0 → deterministic; identity isn't lock-pinned).
        Path jarOut = layout.mainJar();
        Files.createDirectories(jarOut.getParent());
        new JarPackager()
                .packageJar(new JarPackager.JarRequest(
                        classes, jarOut, project.project().main(), 0L, Map.of()));
        byte[] jar = Files.readAllBytes(jarOut);

        // 4. Render the POM, stamped with the published coordinate + version.
        String pomXml = PublishablePom.render(
                        withCoordinate(project, group, artifact, version), PublishablePom.Metadata.empty())
                .xml();

        return new Built(group, artifact, version, jar, pomXml);
    }

    /** A copy of {@code project} whose {@code [project]} coordinate/version are replaced. */
    private static JkBuild withCoordinate(JkBuild project, String group, String artifact, String version) {
        JkBuild.Project p = project.project();
        JkBuild.Project overridden = new JkBuild.Project(
                group,
                artifact,
                version,
                p.jdk(),
                p.graal(),
                p.java(),
                p.kotlin(),
                p.main(),
                p.shadow(),
                p.nativeMode(),
                p.description(),
                p.application(),
                p.m2install(),
                p.layout());
        return new JkBuild(
                overridden,
                project.dependencies(),
                project.repositories(),
                project.profiles(),
                project.features(),
                project.workspace(),
                project.manifest());
    }

    /** Copy a source tree into {@code dest}; no-op if absent. */
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
