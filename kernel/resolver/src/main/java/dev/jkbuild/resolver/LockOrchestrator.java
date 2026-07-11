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
            List.of(
                    Scope.EXPORT,
                    Scope.MAIN,
                    Scope.RUNTIME,
                    Scope.PROVIDED,
                    Scope.TEST,
                    Scope.PROCESSOR,
                    Scope.DEV,
                    Scope.TEST_DEV);

    /**
     * jk test infrastructure: always injected into the TEST classpath via {@code putIfAbsent} so
     * {@code jk test} (which forks {@code jk-test-runner} over the JUnit Platform Launcher API)
     * works regardless of which test framework the user chose.
     */
    private static final Dependency JUNIT_LAUNCHER =
            new Dependency("org.junit.platform:junit-platform-launcher", VersionSelector.parse("latest"));

    /**
     * Passive JUnit 5 default: injected only when the user declared no {@code [test-dependencies]}
     * section, so that a bare project gets a working test framework out of the box. Once the user
     * owns the section — even if they don't list JUnit — jk leaves the framework choice to them.
     *
     * <p>Declared as {@code latest}: {@code jk lock} pins today's latest stable release (reproducible
     * builds), and {@code jk update} advances it — "jk defaults to the latest stable JUnit" stays
     * evergreen without manual bumps.
     */
    private static final Dependency JUNIT_JUPITER =
            new Dependency("org.junit.jupiter:junit-jupiter", VersionSelector.parse("latest"));

    /** Both default test deps together — used only for the no-test-section fast path. */
    private static final List<Dependency> DEFAULT_TEST_DEPS = List.of(JUNIT_JUPITER, JUNIT_LAUNCHER);

    private final RepoGroup repos;
    private final Resolver resolverOverride;
    private dev.jkbuild.resolver.pubgrub.Diagnostics.Palette diagnosticPalette;

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

    /**
     * Inject a {@link dev.jkbuild.resolver.pubgrub.Diagnostics.Palette} built from the CLI theme
     * so version colors in conflict messages match the live {@code coordVersion()} color. Returns
     * {@code this} for chaining.
     */
    public LockOrchestrator withDiagnosticPalette(dev.jkbuild.resolver.pubgrub.Diagnostics.Palette palette) {
        this.diagnosticPalette = palette;
        return this;
    }

    private PubGrubResolver buildResolver(
            dev.jkbuild.repo.RepoGroup repos,
            java.util.Map<String, String> bomConstraints,
            java.util.Map<String, String> lockedVersionPrefs) {
        PubGrubResolver r = new PubGrubResolver(repos, bomConstraints, lockedVersionPrefs);
        if (diagnosticPalette != null) r.palette = diagnosticPalette;
        return r;
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
        // junit-platform-launcher is jk test infrastructure — always inject it;
        // putIfAbsent lets a user-declared launcher version win.
        deduped.putIfAbsent(JUNIT_LAUNCHER.module(), JUNIT_LAUNCHER);
        // junit-jupiter is the passive default: only inject it when the user
        // declared no [test-dependencies] section. Once they own that section
        // (even without a junit entry) the framework choice is theirs.
        if (project.dependencies().of(Scope.TEST).isEmpty()) {
            deduped.putIfAbsent(JUNIT_JUPITER.module(), JUNIT_JUPITER);
        }
        // Partition: sha256-pinned file deps are already resolved — they carry
        // their own blob identity and never need PubGrub or a network fetch. By
        // this point every git dep has already been rewritten to a plain
        // coordinate pin by GitSourceResolution.prepare, so nothing reaching here
        // is still git- or path-sourced.
        List<Dependency> fileDeps = new ArrayList<>();
        List<Dependency> declared = new ArrayList<>();
        for (Dependency d : deduped.values()) {
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

        // A direct dependency with an EXACT (=) version beats the BOM's managed pin for that
        // coordinate — Maven/Gradle semantics (management supplies defaults, never overrides an
        // explicit declaration). Floating selectors (^ / ~ / ranges / latest) keep the BOM pin:
        // they express "any compatible", and the BOM's curated version is the compatible choice.
        for (Dependency d : declared) {
            if (d.isPlatformManaged()) continue;
            if (!(d.version() instanceof dev.jkbuild.model.VersionSelector.Exact)) continue;
            if (bomConstraints.containsKey(d.module())) {
                bomConstraints.remove(d.module());
                constraintProvenance.remove(d.module());
            }
        }

        // Platform-managed root deps (declared with no version) pin from the BOM constraints
        // gathered above — the Maven "managed dependency" semantics Boot starters rely on.
        List<Dependency> roots = new ArrayList<>(declared.size());
        for (Dependency d : declared) {
            if (d.isPlatformManaged()) {
                String managed = bomConstraints.get(d.module());
                if (managed == null) {
                    throw new IllegalStateException("`" + d.module()
                            + "` is declared without a version, but no [platform-dependencies] BOM manages it"
                            + " — add a `version`, or import the BOM that pins it.");
                }
                roots.add(new Dependency(
                        d.library(),
                        d.module(),
                        dev.jkbuild.model.VersionSelector.parse("=" + managed),
                        null,
                        null,
                        true,
                        d.optional()));
            } else {
                roots.add(d);
            }
        }

        Resolver resolver = resolverOverride != null
                ? resolverOverride
                : buildResolver(repos, bomConstraints, lockedVersionPrefs);
        Resolution resolution = resolver.resolve(roots);
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
                // Launcher is always in deduped — seed its transitive closure into TEST scope.
                rootModules.add(JUNIT_LAUNCHER.module());
                // Jupiter is only in deduped when passively injected (no explicit test section).
                if (project.dependencies().of(Scope.TEST).isEmpty()) {
                    rootModules.add(JUNIT_JUPITER.module());
                }
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
            Coordinate coord = mod.coordinate();

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
            if (pkg.name().indexOf(':') < 0) {
                updated.add(pkg);
                continue;
            }
            Coordinate sourcesCoord =
                    new Coordinate(pkg.moduleGroup(), pkg.moduleArtifact(), pkg.version(), "sources", "jar");
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
