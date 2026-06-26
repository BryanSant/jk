// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compile.ClasspathResolver;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.layout.BuildLayout;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Locates the already-built jars (and their external transitive deps) of a
 * consumer's composite ({@code path} / branch-git) dependencies, WITHOUT
 * building anything — the build driver ({@link BuildGraph} + the pipeline) has
 * already built every composite unit by the time a consumer's classpath is
 * assembled. The composite analog of {@code WorkspaceClasspath} (which locates
 * sibling jars); the two run side by side and are deduped at the classpath.
 *
 * <p>Seeded by the consumer's deps in {@code depScopes}; each target's own
 * {@code MAIN} composite deps propagate transitively (so a consumer of A which
 * path-depends on B gets both jars). Each target's external (Maven) deps come
 * from its on-disk {@code jk.lock} via {@link ClasspathResolver}.
 */
public final class CompositeLocator {

    /**
     * @param jars            built main jars of the consumer's composite deps (direct + transitive)
     * @param externalDepJars each target's external Maven deps (from its jk.lock)
     * @param missing         coords whose jar isn't built yet (clear, like WorkspaceClasspath)
     */
    public record Located(List<Path> jars, List<Path> externalDepJars, List<String> missing) {
        public boolean isEmpty() {
            return jars.isEmpty() && externalDepJars.isEmpty();
        }
    }

    private CompositeLocator() {}

    /**
     * A shared external coordinate resolved to different versions across the
     * composite boundary — both jars land on the consumer's classpath (deduped by
     * path, not coordinate), so they coexist unreconciled. {@code versionBySource}
     * maps each project ({@code group:artifact}) to the version it locked.
     *
     * <p>jk surfaces what Gradle/Maven sidestep; the shape (coordinate + versions +
     * who requires each) is exactly what an assisted reconciler would consume.
     */
    public record VersionConflict(String coord, Map<String, String> versionBySource) {}

    /**
     * Detect external-dependency version disagreements between the consumer and its
     * composite ({@code path}/branch-git) targets (and among targets). Each project
     * resolves its own {@code jk.lock} independently — there is no cross-boundary
     * unification — so a shared coordinate can resolve to different versions.
     */
    public static List<VersionConflict> conflicts(Path consumerDir, JkBuild consumer, Path gitRoot)
            throws IOException, InterruptedException {
        BuildGraph.Result graph = BuildGraph.resolve(consumerDir, consumer, gitRoot);
        if (graph.hasErrors()) return List.of();
        // coord (group:artifact) → (source project coord → its locked version)
        java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, String>> byCoord =
                new java.util.LinkedHashMap<>();
        for (BuildGraph.BuildUnit u : graph.topoOrder()) {
            Path lock = u.dir().resolve("jk.lock");
            if (!Files.isRegularFile(lock)) continue;
            for (Lockfile.Artifact a : LockfileReader.read(lock).artifacts()) {
                if (a.version() == null || a.version().isBlank()) continue;
                byCoord.computeIfAbsent(a.name(), k -> new java.util.LinkedHashMap<>())
                        .putIfAbsent(u.coord(), a.version());
            }
        }
        List<VersionConflict> out = new ArrayList<>();
        for (var e : byCoord.entrySet()) {
            if (e.getValue().values().stream().distinct().count() > 1) {
                out.add(new VersionConflict(e.getKey(), e.getValue()));
            }
        }
        return out;
    }

    public static Located locate(
            Path consumerDir,
            JkBuild consumer,
            Set<Scope> depScopes,
            Set<Scope> externalCpScopes,
            Cas cas,
            Path gitRoot)
            throws IOException, InterruptedException {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        LinkedHashSet<Path> externalDepJars = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        LinkedHashSet<Path> visited = new LinkedHashSet<>();
        ClasspathResolver resolver = new ClasspathResolver(cas);

        // Seed from the consumer's composite deps declared in the requested scopes.
        for (Dependency dep : compositeDepsIn(consumer, depScopes)) {
            walk(consumerDir, dep, externalCpScopes, gitRoot, resolver, visited, jars, externalDepJars, missing);
        }
        return new Located(new ArrayList<>(jars), new ArrayList<>(externalDepJars), missing);
    }

    private static void walk(
            Path fromDir,
            Dependency dep,
            Set<Scope> externalCpScopes,
            Path gitRoot,
            ClasspathResolver resolver,
            LinkedHashSet<Path> visited,
            LinkedHashSet<Path> jars,
            LinkedHashSet<Path> externalDepJars,
            List<String> missing)
            throws IOException, InterruptedException {
        Path targetDir = BuildGraph.targetDir(fromDir, dep, gitRoot);
        Path key = canonical(targetDir);
        if (!visited.add(key)) return;

        Path toml = targetDir.resolve("jk.toml");
        if (!Files.isRegularFile(toml)) {
            missing.add(dep.module() + " (no jk.toml at " + targetDir + ")");
            return;
        }
        JkBuild target;
        try {
            target = JkBuildParser.parse(Files.readString(toml));
        } catch (RuntimeException e) {
            missing.add(dep.module() + " (unparseable jk.toml: " + e.getMessage() + ")");
            return;
        }

        Path jar = BuildLayout.of(targetDir, target).mainJar();
        if (Files.isRegularFile(jar)) {
            jars.add(jar);
        } else {
            missing.add(dep.module() + " (not built — expected " + jar + ")");
        }

        // The target's external transitive deps, from its own lock (locate-only).
        Path lock = targetDir.resolve("jk.lock");
        if (Files.isRegularFile(lock)) {
            externalDepJars.addAll(resolver.classpathFor(LockfileReader.read(lock), externalCpScopes));
        }

        // Only MAIN composite deps propagate transitively (cf. WorkspaceClasspath).
        for (Dependency child : compositeDepsIn(target, Set.of(Scope.MAIN))) {
            walk(targetDir, child, externalCpScopes, gitRoot, resolver, visited, jars, externalDepJars, missing);
        }
    }

    /** Composite deps declared in any of {@code scopes}, deduped by module. */
    private static List<Dependency> compositeDepsIn(JkBuild project, Set<Scope> scopes) {
        List<Dependency> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Scope scope : scopes) {
            for (Dependency d : project.dependencies().of(scope)) {
                if (BuildGraph.isComposite(d) && seen.add(d.module())) out.add(d);
            }
        }
        return out;
    }

    private static Path canonical(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
