// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.Scope;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.EffectivePom;
import dev.jkbuild.repo.EffectivePomBuilder;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.Pom;
import dev.jkbuild.repo.RepoGroup;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * Composes the resolver pipeline end-to-end: parsed {@link JkBuild} -> {@link Resolution} ->
 * artifact downloads -> {@link Lockfile}.
 *
 * <p>One resolution pass over the union of all scope roots (plus any deps contributed by activated
 * features). After the solver returns, BFS the resolved graph from each scope's declared roots
 * independently to tag every package with the scopes whose roots reach it. Downstream {@link
 * dev.jkbuild.compile.ClasspathResolver} filters by scope so test deps don't pollute the main
 * classpath.
 */
public final class LockOrchestrator {

    private static final List<Scope> SCOPES =
            List.of(Scope.EXPORT, Scope.MAIN, Scope.RUNTIME, Scope.PROVIDED, Scope.TEST, Scope.PROCESSOR);

    /**
     * The JUnit Platform deps jk adds to every project's TEST scope so that {@code jk test} (which
     * forks {@code jk-test-runner} over the JUnit Platform) works out of the box — the user never
     * mentions these in {@code jk.toml}.
     *
     * <p>Declared as {@code latest}, not a pinned version: {@code jk lock} records today's latest
     * <em>stable</em> release into {@code jk.lock} (reproducible), and {@code jk update} advances it
     * — so "jk defaults to the latest stable JUnit" stays evergreen. Unbounded {@code latest} is safe
     * because the runner jar bundles no JUnit; it drives whatever launcher + engines resolve here
     * through the stable JUnit Platform Launcher API, so a newer JUnit is always fine.
     *
     * <p>Injected on every lock (see {@link #lock}). For a project that declared no test deps this
     * <em>is</em> the default test framework; when the user did declare their own, {@code
     * putIfAbsent} keeps their chosen {@code junit-jupiter} version and just ensures the launcher is
     * present.
     */
    private static final List<Dependency> DEFAULT_TEST_DEPS = List.of(
            new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("latest")),
            new Dependency("org.junit.platform:junit-platform-launcher", VersionSelector.parse("latest")));

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
    public Lockfile lock(JkBuild project, String jkVersion) throws IOException, InterruptedException {
        return lock(project, jkVersion, List.of(), true, ResolveObserver.NOOP);
    }

    /**
     * Lock and additionally attempt to resolve the {@code -sources.jar} for every Maven package,
     * populating {@link Lockfile.Artifact#sourcesChecksum()} when found. Sources that return 404 are
     * silently skipped — not all packages publish sources.
     */
    public Lockfile lockWithSources(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        Lockfile base = lock(project, jkVersion, featuresRequested, withDefaults, observer, Map.of());
        return attachSources(base);
    }

    public Lockfile lock(JkBuild project, String jkVersion, Collection<String> featuresRequested, boolean withDefaults)
            throws IOException, InterruptedException {
        return lock(project, jkVersion, featuresRequested, withDefaults, ResolveObserver.NOOP);
    }

    /**
     * Lock with an explicit feature selection and a progress observer. {@link
     * ResolveObserver#onTotal} fires once after the solver returns the full decision map; {@link
     * ResolveObserver#onPackage} fires once per package as each artifact is fetched and recorded.
     */
    public Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        return lock(project, jkVersion, featuresRequested, withDefaults, observer, Map.of());
    }

    /**
     * Conservative re-lock: same as {@link #lock} but seeds the solver with the exact versions from
     * {@code existing} as <em>soft preferences</em>. The solver selects each locked version first; if
     * a new or changed dep's constraint rules it out, the solver backtracks to the next candidate
     * automatically. Only versions that genuinely conflict with new constraints are bumped —
     * everything else stays pinned.
     */
    public Lockfile lockConservative(
            JkBuild project,
            Lockfile existing,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer)
            throws IOException, InterruptedException {
        Map<String, String> prefs = new HashMap<>();
        for (Lockfile.Artifact pkg : existing.artifacts()) {
            prefs.put(pkg.name(), pkg.version());
        }
        return lock(project, jkVersion, featuresRequested, withDefaults, observer, prefs);
    }

    private Lockfile lock(
            JkBuild project,
            String jkVersion,
            Collection<String> featuresRequested,
            boolean withDefaults,
            ResolveObserver observer,
            Map<String, String> lockedVersionPrefs)
            throws IOException, InterruptedException {

        Set<String> activated = project.features().activate(new LinkedHashSet<>(featuresRequested), withDefaults);

        // Declared-scope roots, deduped (declared wins). Optional deps are
        // WITHHELD here — they enter only when an activated feature names them.
        LinkedHashMap<String, Dependency> deduped = new LinkedHashMap<>();
        LinkedHashMap<String, Dependency> optionalByLib = new LinkedHashMap<>();
        for (Scope scope : SCOPES) {
            for (Dependency dep : project.dependencies().of(scope)) {
                if (dep.optional()) {
                    optionalByLib.putIfAbsent(dep.library(), dep);
                } else {
                    deduped.putIfAbsent(dep.module(), dep);
                }
            }
        }
        // Pull in the optional deps named by the activated features (by their
        // [dependencies.*] short name). A name that isn't a declared optional
        // dep is a config error — most likely a missing `optional = true`.
        for (String depName : project.features().requestedDepNames(activated)) {
            Dependency opt = optionalByLib.get(depName);
            if (opt == null) {
                throw new IllegalArgumentException("feature dependency '"
                        + depName
                        + "' is not a declared optional dependency"
                        + " — declare it under [dependencies.*] with `optional = true`");
            }
            deduped.putIfAbsent(opt.module(), opt);
        }
        // jk drives `jk test` over the JUnit Platform, so it always seeds the
        // test classpath with the launcher + a default engine. When the user
        // declared no test deps this is the default test framework (latest
        // stable JUnit Jupiter); when they did, putIfAbsent ensures the launcher
        // is present without overriding the versions they chose.
        for (Dependency dflt : DEFAULT_TEST_DEPS) {
            deduped.putIfAbsent(dflt.module(), dflt);
        }
        // Partition: sha256-pinned file deps are already resolved — they carry
        // their own blob identity and never need PubGrub or a network fetch.
        // Composite source deps (`path = …` and branch git deps) are built from
        // source and injected onto the classpath at build time (the composite build path,
        // jk's includeBuild analog); they are NOT coordinates the resolver can
        // resolve and are never written to the lock, so drop them here.
        List<Dependency> fileDeps = new ArrayList<>();
        List<Dependency> declared = new ArrayList<>();
        for (Dependency d : deduped.values()) {
            if (isComposite(d)) continue;
            if (d.isFile()) fileDeps.add(d);
            else declared.add(d);
        }

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
            Coordinate bomCoord = Coordinate.of(platformDep.group(), platformDep.name(), bomVersion);
            EffectivePom bomPom = pomBuilder.build(bomCoord);
            String bomLabel = bomCoord.toGav();
            for (Pom.Dep m : bomPom.managedDependencies()) {
                if (m.version() == null || m.version().isBlank()) continue;
                String existing = bomConstraints.get(m.module());
                if (existing == null) {
                    bomConstraints.put(m.module(), m.version());
                    constraintProvenance.put(m.module(), bomLabel);
                } else if (!existing.equals(m.version())) {
                    throw new IllegalStateException("platform BOM conflict on `"
                            + m.module()
                            + "`: "
                            + constraintProvenance.get(m.module())
                            + " constrains to "
                            + existing
                            + ", but "
                            + bomLabel
                            + " constrains to "
                            + m.version()
                            + ". Pick one BOM or pin the coord explicitly.");
                }
            }
        }

        Resolver resolver = resolverOverride != null
                ? resolverOverride
                : new PubGrubResolver(repos, bomConstraints, lockedVersionPrefs);
        Resolution resolution = resolver.resolve(declared);
        observer.onTotal(resolution.modules().size() + fileDeps.size());

        // For each scope, BFS the resolution graph from that scope's roots.
        // Feature-activated optional deps are declared under a scope too, so
        // they're already covered here (and an un-activated optional dep simply
        // isn't in the resolution graph, so it contributes nothing).
        Map<String, EnumSet<Scope>> tagsByModule = new HashMap<>();
        for (Scope scope : SCOPES) {
            Set<String> rootModules = new HashSet<>();
            for (Dependency d : project.dependencies().of(scope)) {
                rootModules.add(d.module());
            }
            if (scope == Scope.TEST) {
                // The default JUnit deps seed the TEST scope so their transitive
                // closure lands on the test-runtime classpath.
                for (Dependency d : DEFAULT_TEST_DEPS) rootModules.add(d.module());
            }
            if (rootModules.isEmpty()) continue;
            for (String module : reachableFrom(rootModules, resolution)) {
                tagsByModule
                        .computeIfAbsent(module, k -> EnumSet.noneOf(Scope.class))
                        .add(scope);
            }
        }

        MavenRepo first = repos.repos().getFirst();
        String fallbackSource = first.name() + "+" + first.baseUrl();

        List<Lockfile.Artifact> packages = new ArrayList<>(resolution.modules().size());
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            int colon = mod.module().indexOf(':');
            Coordinate coord =
                    Coordinate.of(mod.module().substring(0, colon), mod.module().substring(colon + 1), mod.version());

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

            packages.add(new Lockfile.Artifact(
                    mod.module(), mod.version(), source, checksum, null, new ArrayList<>(tags), mod.deps(), pinnedBy));
        }

        // File deps: emit a lockfile entry directly — no solver, no network.
        // CacheSync skips them on sync (cas.contains is true after jk install);
        // ClasspathResolver resolves them via the checksum like any other dep.
        for (Dependency dep : fileDeps) {
            String version = dep.version() instanceof VersionSelector.Exact e
                    ? e.version()
                    : dep.version().raw();
            observer.onPackage(dep.module(), version);
            EnumSet<Scope> tags = EnumSet.noneOf(Scope.class);
            for (Scope scope : SCOPES) {
                for (Dependency d : project.dependencies().of(scope)) {
                    if (d.isFile() && d.module().equals(dep.module())) tags.add(scope);
                }
            }
            if (tags.isEmpty()) tags.add(Scope.MAIN);
            packages.add(new Lockfile.Artifact(
                    dep.module(),
                    version,
                    "local",
                    "sha256:" + dep.sha256(),
                    null,
                    new ArrayList<>(tags),
                    List.of(),
                    null));
        }

        return new Lockfile(Lockfile.CURRENT_VERSION, "jk " + jkVersion, Lockfile.RESOLUTION_ALGORITHM, packages);
    }

    /**
     * Extract a concrete version literal from a platform-dep's selector. Platform BOMs must be pinned
     * (Exact) or anchored (Caret/Tilde) to a specific version — they're an authoritative pin, not a
     * search. Returns {@code null} for selectors with no resolvable literal (Range, Latest).
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

    /**
     * A composite source dependency — built from source and injected onto the classpath at build time
     * rather than resolved as a Maven coordinate: a {@code path = …} dep, or a <em>branch</em> git
     * dep (a moving target). Immutable (tag/rev) git deps are materialized to a pin upstream ({@code
     * GitSourceResolution}) and arrive here as ordinary coordinates.
     */
    private static boolean isComposite(Dependency d) {
        return d.isPath() || (d.isGit() && !d.gitSource().ref().isImmutable());
    }

    /**
     * Try to fetch {@code -sources.jar} for every Maven package in {@code lock} and return a copy
     * with {@link Lockfile.Artifact#sourcesChecksum()} populated where sources exist. Packages that
     * return 404, have a non-maven source, or already have a sources checksum are left unchanged.
     */
    public Lockfile attachSources(Lockfile lock) throws InterruptedException {
        List<Lockfile.Artifact> updated = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            // Skip non-Maven packages (git, local, file deps) and those already resolved.
            if (!pkg.source().contains("maven") && !pkg.source().startsWith("central")
                    || pkg.sourcesChecksum() != null) {
                updated.add(pkg);
                continue;
            }
            int colon = pkg.name().indexOf(':');
            if (colon < 0) {
                updated.add(pkg);
                continue;
            }
            Coordinate sourcesCoord = new Coordinate(
                    pkg.name().substring(0, colon), pkg.name().substring(colon + 1), pkg.version(), "sources", "jar");
            try {
                RepoGroup.RepoFetched hit = repos.tryFetchArtifact(sourcesCoord).orElse(null);
                if (hit != null) {
                    updated.add(new Lockfile.Artifact(
                            pkg.name(),
                            pkg.version(),
                            pkg.source(),
                            pkg.checksum(),
                            pkg.path(),
                            pkg.scopes(),
                            pkg.deps(),
                            pkg.pinnedBy(),
                            pkg.git(),
                            "sha256:" + hit.fetched().sha256()));
                    continue;
                }
            } catch (Exception ignored) {
                /* sources not available for this package */
            }
            updated.add(pkg);
        }
        return new Lockfile(
                lock.version(),
                lock.generatedBy(),
                lock.resolutionAlgorithm(),
                lock.jdk(),
                lock.kotlin(),
                updated,
                lock.plugins());
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
