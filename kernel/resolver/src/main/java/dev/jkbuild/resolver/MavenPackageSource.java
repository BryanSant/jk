// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.model.Coordinate;
import dev.jkbuild.repo.EffectivePom;
import dev.jkbuild.repo.EffectivePomBuilder;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.Pom;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.pubgrub.PackageSource;
import dev.jkbuild.resolver.pubgrub.Term;
import dev.jkbuild.resolver.pubgrub.VersionSet;
import dev.jkbuild.run.JkThreads;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

// Maven BOM (`<dependencyManagement>`) support: when the user declares a
// platform BOM in `[dependencies.platform]`, the LockOrchestrator hands a
// `Map<group:artifact, version>` of all BOM-constrained coords to the
// resolver. We thread it down here so {@link #versions(String)} returns a
// single-element candidate list for any constrained coord — letting
// PubGrub's existing machinery pick the BOM-pinned version without
// changes to the solver itself.

/**
 * Adapts a {@link MavenRepo} + {@link EffectivePomBuilder} into the PubGrub {@link PackageSource}
 * contract.
 *
 * <p>Caches versions and dep lists in-memory per solver run so repeated lookups during conflict
 * resolution don't re-hit the network.
 *
 * <p>After resolving a package's dependency list, the source speculatively prefetches {@code
 * maven-metadata.xml} for each transitive on {@link JkThreads#io()}. PubGrub explores one package
 * at a time, so by the time it asks for those packages' versions the metadata is already in the
 * on-disk cache. A {@link Semaphore} caps concurrent prefetches so we don't get rate-limited by
 * Maven Central; the cache maps are switched to {@link ConcurrentHashMap} so speculative writes are
 * safe.
 */
public final class MavenPackageSource implements PackageSource {

    private static final Set<String> FOLLOWED_SCOPES = Set.of("compile", "runtime");

    /** Max concurrent speculative prefetches. Tuned to stay polite to Maven Central. */
    private static final int PREFETCH_PERMITS = 8;

    private final RepoGroup repos;
    private final EffectivePomBuilder pomBuilder;
    private final Map<String, String> bomConstraints;
    private final KmpRedirects kmp;

    /** Locked versions from a prior lock file — preferred but NOT hard-pinned. */
    private final Map<String, String> lockedVersionPrefs;

    private final Map<String, List<String>> versionCache = new ConcurrentHashMap<>();
    private final Map<String, List<Term>> depsCache = new ConcurrentHashMap<>();
    private final Semaphore prefetchSlots = new Semaphore(PREFETCH_PERMITS);

    public MavenPackageSource(MavenRepo repo, EffectivePomBuilder pomBuilder) {
        this(RepoGroup.of(repo), pomBuilder, Map.of());
    }

    public MavenPackageSource(RepoGroup repos, EffectivePomBuilder pomBuilder) {
        this(repos, pomBuilder, Map.of());
    }

    public MavenPackageSource(RepoGroup repos, EffectivePomBuilder pomBuilder, Map<String, String> bomConstraints) {
        this(repos, pomBuilder, bomConstraints, Map.of());
    }

    /**
     * Conservative-lock variant: {@code lockedVersionPrefs} contains the exact versions from a prior
     * lockfile. Unlike BOM constraints (which hard-pin), these move the locked version to the
     * <em>front</em> of the candidate list. PubGrub will select it first; if a new dep's constraint
     * rules it out, PubGrub naturally backtracks to the next candidate — no manual fallback needed.
     */
    public MavenPackageSource(
            RepoGroup repos,
            EffectivePomBuilder pomBuilder,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs) {
        this(repos, pomBuilder, bomConstraints, lockedVersionPrefs, KmpRedirects.NONE);
    }

    /** As above with KMP root-module redirect resolution (see {@link KmpRedirects}). */
    public MavenPackageSource(
            RepoGroup repos,
            EffectivePomBuilder pomBuilder,
            Map<String, String> bomConstraints,
            Map<String, String> lockedVersionPrefs,
            KmpRedirects kmp) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.pomBuilder = Objects.requireNonNull(pomBuilder, "pomBuilder");
        this.bomConstraints = Map.copyOf(Objects.requireNonNull(bomConstraints, "bomConstraints"));
        this.lockedVersionPrefs = Map.copyOf(Objects.requireNonNull(lockedVersionPrefs, "lockedVersionPrefs"));
        this.kmp = Objects.requireNonNull(kmp, "kmp");
    }

    @Override
    public List<String> versions(String pkg) throws IOException, InterruptedException {
        // BOM constraint: a user-declared platform BOM (or one it imports) pinned this coord.
        // Return that single version as the only candidate so PubGrub's "first satisfying
        // version wins" loop picks it; a transitive that demands a range excluding the pin
        // surfaces a clean "constraint cannot hold" diagnostic.
        //
        // KNOWN DIVERGENCE (android-plan A5e finding 13, parked): Gradle's platform() is a
        // recommendation — a stricter transitive floor lifts above the pin (navigation3 1.0.0
        // vs the 2025.09 compose BOM; Gradle resolves it, jk reports the conflict). Decided
        // semantics: the lockedVersionPrefs shape (full candidate list, pin first,
        // highest-wins on a lift). Blocked on solver scaling — even with the propagation
        // watch index, wide candidate lists across dozens of interlocking compose pins drown
        // in VersionSet.Union intersections (one interval per excluded version; every
        // relationTo intersects them). Needs per-package version interning (integer indices /
        // interval trees) before the wide lists are viable. Failing fast beats hanging.
        String pinned = bomConstraints.get(pkg);
        if (pinned != null) {
            List<String> singleton = List.of(pinned);
            versionCache.put(pkg, singleton);
            return singleton;
        }
        List<String> cached = versionCache.get(pkg);
        if (cached != null) return cached;
        // Online this merges each repo's maven-metadata.xml; offline it returns
        // only the versions actually present in the local journal, so PubGrub
        // never picks a version whose POM/jar we can't read without a network.
        List<String> available = repos.availableVersions(withVersion(pkg, "any"));
        // PubGrub picks the first version that satisfies — order highest first.
        List<String> sorted = new ArrayList<>(available);
        sorted.sort((a, b) -> Versions.compare(b, a));
        // Conservative re-lock: if we have a locked version preference for this
        // package, move it to the front of the candidate list. PubGrub will
        // select it first; if a new dep rules it out, PubGrub backtracks to
        // the next candidate naturally — no intervention needed.
        String preferred = lockedVersionPrefs.get(pkg);
        if (preferred != null && sorted.remove(preferred)) {
            sorted.add(0, preferred);
        }
        List<String> result = List.copyOf(sorted);
        versionCache.put(pkg, result);
        return result;
    }

    @Override
    public List<Term> dependencies(String pkg, String version) throws IOException, InterruptedException {
        String key = pkg + "@" + version;
        List<Term> cached = depsCache.get(key);
        if (cached != null) return cached;

        Coordinate coord = withVersion(pkg, version);
        EffectivePom pom;
        try {
            pom = pomBuilder.build(coord);
        } catch (MavenRepo.ArtifactNotFoundException e) {
            // Definitive all-repos 404 for an advertised version (or a parent/BOM in its
            // chain) — a half-published release mid-propagation. Tell the solver to skip
            // exactly this version; genuine network failures are NOT this exception and
            // stay fatal.
            throw new VersionUnavailableException(e.getMessage());
        }
        List<Term> out = new ArrayList<>();
        // KMP root module: substitute the GMM-selected platform artifact for the POM's
        // platform fallback (-jvm), and drop every other platform sibling the variants
        // point at — keeping both would double-define each class at dex/classpath time.
        var kmpSelection = kmp.selectionFor(pkg, version);
        Set<String> kmpDropped = Set.of();
        if (kmpSelection.isPresent()) {
            var target = kmpSelection.get().target();
            // Gradle's available-at is an EXACT module reference — the platform artifact must
            // stay lockstep with its root. A lower bound floats across compatibility lines
            // (kotlinx-datetime 0.6.1's -jvm drifted to 0.8.0-0.6.x-compat, whose API differs).
            out.add(Term.positive(
                    target.group() + ":" + target.module(), VersionSet.exact(target.version())));
            kmpDropped = kmpSelection.get().allTargets();
        }
        for (Pom.Dep dep : pom.dependencies()) {
            if (dep.optional()) continue;
            if (kmpDropped.contains(dep.module())) continue;
            String scope = dep.scope();
            if (scope != null && !scope.isEmpty() && !FOLLOWED_SCOPES.contains(scope)) continue;
            if (dep.version() == null || dep.version().isBlank()) continue;
            // PRD §7.4 — highest-version-wins across the transitive graph
            // (Gradle/Cargo semantics, not Maven's nearest-wins). Treat a
            // POM-declared dep version as a *lower bound*; a higher version
            // brought in by another sibling satisfies it. Strict Maven
            // ranges (rare in practice) still need their own parser.
            out.add(Term.positive(dep.module(), VersionSet.atLeast(dep.version(), true)));
        }
        List<Term> immutable = List.copyOf(out);
        depsCache.put(key, immutable);
        // PubGrub will ask for these packages' versions next. Warm the
        // metadata cache speculatively while it's still chewing on this
        // package — turns a serial RTT chain into a near-flat fetch curve.
        prefetchVersionsAsync(immutable);
        return immutable;
    }

    /**
     * Fire-and-forget metadata fetches for each {@code Term}'s package on {@link JkThreads#io()}.
     * Bounded by {@link #prefetchSlots} so a deep fan-out doesn't blast a remote repo. Failures are
     * swallowed — the solver's own synchronous {@link #versions} call will report the real error if
     * the package is genuinely unreachable.
     */
    private void prefetchVersionsAsync(List<Term> deps) {
        for (Term dep : deps) {
            String pkg = dep.pkg();
            if (versionCache.containsKey(pkg)) continue;
            JkThreads.io().execute(() -> {
                try {
                    prefetchSlots.acquire();
                    try {
                        versions(pkg);
                    } finally {
                        prefetchSlots.release();
                    }
                } catch (Exception ignored) {
                    // best-effort prefetch; surface errors via the sync path
                }
            });
        }
    }

    private static Coordinate withVersion(String pkg, String version) {
        return Coordinate.ofModule(pkg, version);
    }
}
