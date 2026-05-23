// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Scope;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoGroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Composes the resolver pipeline end-to-end: parsed {@link JkBuild} ->
 * {@link Resolution} -> artifact downloads -> {@link Lockfile}.
 *
 * <p>One resolution pass over the union of all scope roots (plus any
 * deps contributed by activated features). After the solver returns,
 * BFS the resolved graph from each scope's declared roots independently
 * to tag every package with the scopes whose roots reach it.
 * Downstream {@link dev.jkbuild.compile.ClasspathResolver} filters by
 * scope so test deps don't pollute the main classpath.
 */
public final class LockOrchestrator {

    private static final List<Scope> SCOPES =
            List.of(Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST);

    private final RepoGroup repos;
    private final Resolver resolver;

    public LockOrchestrator(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public LockOrchestrator(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolver = new PubGrubResolver(repos);
    }

    /** Test seam: lets tests inject a different resolver (e.g. NaiveResolver). */
    LockOrchestrator(RepoGroup repos, Resolver resolver) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** Lock with the project's default feature selection. */
    public Lockfile lock(JkBuild project, String jkVersion) throws IOException, InterruptedException {
        return lock(project, jkVersion, List.of(), true);
    }

    /**
     * Lock with an explicit feature selection. {@code featuresRequested}
     * are added on top of the project's default feature list (gated by
     * {@code withDefaults}). The expanded feature deps fold into the
     * MAIN scope's roots.
     */
    public Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults) throws IOException, InterruptedException {

        Set<String> activated = project.features().activate(
                new LinkedHashSet<>(featuresRequested), withDefaults);
        List<Dependency> featureDeps = project.features().resolveDeps(activated);

        // Union of declared-scope roots + feature deps (deduped — declared wins).
        LinkedHashMap<String, Dependency> deduped = new LinkedHashMap<>();
        for (Scope scope : SCOPES) {
            for (Dependency dep : project.dependencies().of(scope)) {
                deduped.putIfAbsent(dep.module(), dep);
            }
        }
        for (Dependency featureDep : featureDeps) {
            deduped.putIfAbsent(featureDep.module(), featureDep);
        }
        List<Dependency> declared = new ArrayList<>(deduped.values());
        Resolution resolution = resolver.resolve(declared);

        // For each scope, BFS the resolution graph from that scope's roots
        // (feature deps act as additional main-scope roots).
        Map<String, EnumSet<Scope>> tagsByModule = new HashMap<>();
        for (Scope scope : SCOPES) {
            Set<String> rootModules = new HashSet<>();
            for (Dependency d : project.dependencies().of(scope)) {
                rootModules.add(d.module());
            }
            if (scope == Scope.MAIN) {
                for (Dependency d : featureDeps) rootModules.add(d.module());
            }
            if (rootModules.isEmpty()) continue;
            for (String module : reachableFrom(rootModules, resolution)) {
                tagsByModule.computeIfAbsent(module, k -> EnumSet.noneOf(Scope.class)).add(scope);
            }
        }

        MavenRepo first = repos.repos().getFirst();
        String fallbackSource = first.name() + "+" + first.baseUrl();

        List<Lockfile.Package> packages = new ArrayList<>(resolution.modules().size());
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            int colon = mod.module().indexOf(':');
            Coordinate coord = Coordinate.of(
                    mod.module().substring(0, colon),
                    mod.module().substring(colon + 1),
                    mod.version());

            String source = fallbackSource;
            String checksum = null;
            RepoGroup.RepoFetched hit = repos.tryFetchArtifact(coord).orElse(null);
            if (hit != null) {
                source = hit.repo().name() + "+" + hit.repo().baseUrl();
                checksum = "sha256:" + hit.fetched().sha256();
            }

            EnumSet<Scope> tags = tagsByModule.getOrDefault(mod.module(), EnumSet.of(Scope.MAIN));

            packages.add(new Lockfile.Package(
                    mod.module(),
                    mod.version(),
                    source,
                    checksum,
                    null,
                    new ArrayList<>(tags),
                    mod.deps()));
        }

        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk " + jkVersion,
                Lockfile.RESOLUTION_ALGORITHM,
                packages);
    }

    /** BFS through the resolved graph starting from {@code roots}. */
    private static Set<String> reachableFrom(Set<String> roots, Resolution resolution) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            String module = queue.poll();
            if (!visited.add(module)) continue;
            Resolution.ResolvedModule resolved = resolution.modules().get(module);
            if (resolved == null) continue;
            for (String depRef : resolved.deps()) {
                int at = depRef.indexOf('@');
                queue.add(at > 0 ? depRef.substring(0, at) : depRef);
            }
        }
        return visited;
    }
}
