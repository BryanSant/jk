// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.EffectivePom;
import dev.jkbuild.repo.EffectivePomBuilder;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.Pom;
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
            List.of(Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST, Scope.PROCESSOR);

    /**
     * Synthetic deps every project's TEST scope inherits. The user never
     * mentions these in {@code jk.toml}; they're injected here so that
     * {@code jk test} (which forks a JVM running {@code jk-test-runner}
     * over JUnit Platform) always has the engines + launcher API on the
     * test-runtime classpath. Pinned to the JUnit version jk itself was
     * built against — kept here as a single source of truth.
     *
     * <p>{@code putIfAbsent} semantics in {@link #lock} mean a user-declared
     * {@code junit-jupiter} (any version) wins over the synthetic pin, so
     * teams already on a specific JUnit release are unaffected.
     */
    public static final String JK_TEST_JUNIT_VERSION = "6.1.0";

    private static final List<Dependency> IMPLICIT_TEST_DEPS = List.of(
            // Caret-implicit (parseFloating) — 6.x is wire-compatible with
            // the launcher API our test-runner was built against; users on
            // 6.1.1+ pick up patch releases automatically.
            new Dependency(
                    "org.junit.jupiter:junit-jupiter",
                    VersionSelector.parseFloating(JK_TEST_JUNIT_VERSION)),
            new Dependency(
                    "org.junit.platform:junit-platform-launcher",
                    VersionSelector.parseFloating(JK_TEST_JUNIT_VERSION)));

    private final RepoGroup repos;
    private final Resolver resolverOverride;

    public LockOrchestrator(MavenRepo repo) {
        this(RepoGroup.of(repo));
    }

    public LockOrchestrator(RepoGroup repos) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolverOverride = null;
    }

    /** Test seam: lets tests inject a different resolver (e.g. NaiveResolver). */
    LockOrchestrator(RepoGroup repos, Resolver resolver) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.resolverOverride = Objects.requireNonNull(resolver, "resolver");
    }

    /** Lock with the project's default feature selection. */
    /** Lock with the project's default feature selection. */
    public Lockfile lock(JkBuild project, String jkVersion) throws IOException, InterruptedException {
        return lock(project, jkVersion, List.of(), true, ResolveObserver.NOOP);
    }

    public Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults) throws IOException, InterruptedException {
        return lock(project, jkVersion, featuresRequested, withDefaults, ResolveObserver.NOOP);
    }

    /**
     * Lock with an explicit feature selection and a progress observer.
     * {@link ResolveObserver#onTotal} fires once after the solver returns the
     * full decision map; {@link ResolveObserver#onPackage} fires once per
     * package as each artifact is fetched and recorded.
     */
    public Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer) throws IOException, InterruptedException {

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
        // Inject the test-runner's required JUnit Platform deps when the
        // project actually has test-scope deps declared — a strong signal
        // that the user runs `jk test`. Projects with an empty TEST scope
        // (libraries-with-no-tests, scratch projects) skip the injection
        // so their lockfile stays minimal. `putIfAbsent` keeps the user's
        // choice when they've declared their own JUnit version.
        boolean injectTestPlatform = !project.dependencies().of(Scope.TEST).isEmpty();
        if (injectTestPlatform) {
            for (Dependency implicit : IMPLICIT_TEST_DEPS) {
                deduped.putIfAbsent(implicit.module(), implicit);
            }
        }
        List<Dependency> declared = new ArrayList<>(deduped.values());

        // Gather BOM constraints from `[dependencies.platform]` deps. Each
        // platform dep's effective POM contributes its managedDependencies
        // (BOM imports already expanded by EffectivePomBuilder) as
        // group:artifact → pinned version. Provenance is tracked per coord
        // so we can surface the BOM source in `pinned-by` and in conflict
        // diagnostics.
        Map<String, String> bomConstraints = new LinkedHashMap<>();
        Map<String, String> constraintProvenance = new LinkedHashMap<>();
        EffectivePomBuilder pomBuilder = new EffectivePomBuilder(repos);
        for (Dependency platformDep : project.dependencies().of(Scope.PLATFORM)) {
            String bomVersion = versionLiteral(platformDep.version());
            if (bomVersion == null) {
                // Floating BOM selectors aren't supported yet — the BOM
                // version *is* the pin. Skip gracefully so a misconfigured
                // floating platform dep doesn't take down the entire lock.
                continue;
            }
            Coordinate bomCoord = Coordinate.of(
                    platformDep.group(), platformDep.artifact(), bomVersion);
            EffectivePom bomPom = pomBuilder.build(bomCoord);
            String bomLabel = bomCoord.toGav();
            for (Pom.Dep m : bomPom.managedDependencies()) {
                if (m.version() == null || m.version().isBlank()) continue;
                String existing = bomConstraints.get(m.module());
                if (existing == null) {
                    bomConstraints.put(m.module(), m.version());
                    constraintProvenance.put(m.module(), bomLabel);
                } else if (!existing.equals(m.version())) {
                    throw new IllegalStateException(
                            "platform BOM conflict on `" + m.module()
                                    + "`: " + constraintProvenance.get(m.module())
                                    + " constrains to " + existing + ", but "
                                    + bomLabel + " constrains to " + m.version()
                                    + ". Pick one BOM or pin the coord explicitly.");
                }
            }
        }

        Resolver resolver = resolverOverride != null
                ? resolverOverride
                : new PubGrubResolver(repos, bomConstraints);
        Resolution resolution = resolver.resolve(declared);
        observer.onTotal(resolution.modules().size());

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
            if (scope == Scope.TEST && injectTestPlatform) {
                // The implicit JUnit deps seed the TEST scope when injection
                // fired — their transitive closure lands on test-runtime classpath.
                for (Dependency d : IMPLICIT_TEST_DEPS) rootModules.add(d.module());
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

            observer.onPackage(mod.module(), mod.version());

            String source = fallbackSource;
            String checksum = null;
            RepoGroup.RepoFetched hit = repos.tryFetchArtifact(coord).orElse(null);
            if (hit != null) {
                source = hit.repo().name() + "+" + hit.repo().baseUrl();
                checksum = "sha256:" + hit.fetched().sha256();
            }

            EnumSet<Scope> tags = tagsByModule.getOrDefault(mod.module(), EnumSet.of(Scope.MAIN));

            // Stamp `pinned-by` when the BOM pinned this coord and the
            // resolver picked that pinned version. (Equality check guards
            // against a BOM constraint that wasn't actually used — e.g.,
            // user pinned the same coord explicitly to a different version,
            // in which case the explicit pin wins and we don't want to lie
            // about provenance.)
            String pinnedBy = null;
            String constrained = bomConstraints.get(mod.module());
            if (constrained != null && constrained.equals(mod.version())) {
                pinnedBy = constraintProvenance.get(mod.module());
            }

            packages.add(new Lockfile.Package(
                    mod.module(),
                    mod.version(),
                    source,
                    checksum,
                    null,
                    new ArrayList<>(tags),
                    mod.deps(),
                    pinnedBy));
        }

        return new Lockfile(
                Lockfile.CURRENT_VERSION,
                "jk " + jkVersion,
                Lockfile.RESOLUTION_ALGORITHM,
                packages);
    }

    /**
     * Extract a concrete version literal from a platform-dep's selector.
     * Platform BOMs must be pinned (Exact) or anchored (Caret/Tilde) to a
     * specific version — they're an authoritative pin, not a search. Returns
     * {@code null} for selectors with no resolvable literal (Range, Latest).
     */
    private static String versionLiteral(VersionSelector v) {
        return switch (v) {
            case VersionSelector.Exact e -> e.version();
            case VersionSelector.Caret c -> c.version();
            case VersionSelector.Tilde t -> t.version();
            case VersionSelector.Range ignored -> null;
            case VersionSelector.Latest ignored -> null;
        };
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
