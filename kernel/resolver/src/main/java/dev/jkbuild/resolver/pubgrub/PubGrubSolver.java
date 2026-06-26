// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PubGrub solver. Given a root project, its declared dependencies, and a
 * {@link PackageSource} for the rest of the universe, returns a map of
 * package → resolved version.
 *
 * <p>Stage 2: handles conflict-free graphs end-to-end. On the first true
 * conflict (an incompatibility whose every term is already satisfied),
 * throws {@link UnsatisfiableException}. Stage 3 will replace the throw
 * with proper conflict resolution + backtracking.
 *
 * <p>The implementation follows the structure described in
 * <a href="https://nex3.medium.com/pubgrub-2fb6470504f">"PubGrub: Next-
 * Generation Version Solving"</a> and the Dart pub solver reference.
 */
public class PubGrubSolver {

    protected final PackageSource source;
    protected final PartialSolution solution = new PartialSolution();
    protected final List<Incompatibility> incompatibilities = new ArrayList<>();
    protected String rootPkg;

    public PubGrubSolver(PackageSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * @param rootPkg     name of the root project (e.g. {@code com.example:widget})
     * @param rootVersion version of the root project
     * @param rootDeps    positive {@link Term}s — the user-declared dependencies
     */
    public Map<String, String> solve(String rootPkg, String rootVersion, List<Term> rootDeps)
            throws IOException, InterruptedException {
        this.rootPkg = rootPkg;
        Term rootTerm = Term.positive(rootPkg, VersionSet.exact(rootVersion));

        // Each declared dep becomes inco [+root@v, ¬dep] — "if root@v then dep must
        // be in its requested set". Per PubGrub paper §3: a dependency inco contains
        // the parent decision positively and the child requirement negatively.
        for (Term dep : rootDeps) {
            addIncompatibility(new Incompatibility(
                    List.of(rootTerm, dep.invert()), new Incompatibility.Cause.Dependency(rootTerm, dep)));
        }

        // Decide the root up-front. It isn't fetched from the package source —
        // the user's project doesn't live in any Maven repo.
        solution.decide(rootPkg, rootVersion);

        String next = rootPkg;
        while (next != null) {
            propagate(next);
            next = makeDecision();
        }
        return solution.decisions();
    }

    // --- unit propagation --------------------------------------------------

    protected void propagate(String changedPackage) {
        Set<String> changed = new LinkedHashSet<>();
        changed.add(changedPackage);

        while (!changed.isEmpty()) {
            String pkg = removeFirst(changed);
            for (Incompatibility inco : new ArrayList<>(incompatibilities)) {
                if (inco.terms().stream().noneMatch(t -> t.pkg().equals(pkg))) continue;
                PartialSolution.Relation rel = solution.relationTo(inco);
                switch (rel.kind()) {
                    case SATISFIED -> handleConflict(inco);
                    case ALMOST_SATISFIED -> {
                        Term derived = rel.unsatisfied().invert();
                        solution.derive(derived, inco);
                        changed.add(derived.pkg());
                    }
                    case INCONCLUSIVE -> {
                        // nothing to do
                    }
                }
            }
        }
    }

    /**
     * Conflict resolution + backtracking, per PubGrub paper §6.
     *
     * <p>Walks back through the partial solution to find the assignment that
     * "caused" the conflict, derives a new incompatibility by resolving the
     * conflict against that assignment's cause, and repeats until either the
     * new incompatibility is unresolvable (failure) or has only one term at
     * the current decision level. In the latter case we backtrack to the
     * decision level at which the new incompatibility becomes almost-
     * satisfied, then propagation on it derives the saved constraint.
     */
    protected void handleConflict(Incompatibility inco) {
        Incompatibility current = inco;
        while (true) {
            if (isFailure(current)) {
                throw new UnsatisfiableException(current);
            }

            ResolutionStep step = computeResolutionStep(current);

            if (step.mostRecent instanceof PartialSolution.Assignment.Decision
                    || step.previousLevel < step.mostRecent.decisionLevel()) {
                solution.backtrack(step.previousLevel);
                addIncompatibility(current);
                return;
            }

            Incompatibility prior = ((PartialSolution.Assignment.Derivation) step.mostRecent).cause();
            current = resolveIncompatibilities(current, prior, step.mostRecentTerm);
        }
    }

    /** Located the most-recent assignment that satisfies any term in {@code inco}. */
    private record ResolutionStep(Term mostRecentTerm, PartialSolution.Assignment mostRecent, int previousLevel) {}

    private ResolutionStep computeResolutionStep(Incompatibility inco) {
        PartialSolution.Assignment mostRecent = null;
        Term mostRecentTerm = null;
        int previousLevel = 1;
        for (Term term : inco.terms()) {
            PartialSolution.Assignment satisfier = findSatisfier(term);
            if (mostRecent == null || satisfier.globalIndex() > mostRecent.globalIndex()) {
                if (mostRecent != null) {
                    previousLevel = Math.max(previousLevel, mostRecent.decisionLevel());
                }
                mostRecent = satisfier;
                mostRecentTerm = term;
            } else if (satisfier.decisionLevel() != mostRecent.decisionLevel()) {
                previousLevel = Math.max(previousLevel, satisfier.decisionLevel());
            }
        }
        return new ResolutionStep(mostRecentTerm, mostRecent, previousLevel);
    }

    /**
     * Earliest assignment that, together with strictly-earlier assignments
     * about the same package, forces {@code term}'s effective version set
     * to be a superset of the current allowed range. Throws if {@code term}
     * isn't actually satisfied by the current solution (a programmer error).
     */
    private PartialSolution.Assignment findSatisfier(Term term) {
        VersionSet accumulated = VersionSet.ALL;
        for (PartialSolution.Assignment a : solution.assignments()) {
            if (!a.term().pkg().equals(term.pkg())) continue;
            accumulated = accumulated.intersect(a.term().effectiveVersions());
            if (accumulated.subsetOf(term.effectiveVersions())) {
                return a;
            }
        }
        throw new IllegalStateException("term not actually satisfied: " + term + " in " + solution.assignments());
    }

    /**
     * Standard CDCL resolution: remove the shared {@code pivot} term from
     * both incompatibilities, take the (deduplicated, intersected) union of
     * the remaining terms, and record the derivation in the new
     * incompatibility's cause.
     */
    private Incompatibility resolveIncompatibilities(Incompatibility a, Incompatibility b, Term pivot) {
        LinkedHashMap<String, Term> merged = new LinkedHashMap<>();
        for (Incompatibility source : List.of(a, b)) {
            for (Term term : source.terms()) {
                if (term.pkg().equals(pivot.pkg())) continue;
                merged.merge(term.pkg(), term, Term::intersect);
            }
        }
        List<Term> survivors = new ArrayList<>();
        for (Term t : merged.values()) {
            if (!t.effectiveVersions().isEmpty()) survivors.add(t);
        }
        if (survivors.isEmpty()) {
            // Resolved everything → root failure. Encode as a single
            // unsatisfiable term so isFailure() recognizes it.
            survivors = List.of(Term.positive(rootPkg, VersionSet.EMPTY));
        }
        return new Incompatibility(survivors, new Incompatibility.Cause.Derived(a, b));
    }

    private boolean isFailure(Incompatibility inco) {
        if (inco.terms().isEmpty()) return true;
        return inco.terms().size() == 1 && inco.terms().getFirst().pkg().equals(rootPkg);
    }

    // --- decisions ---------------------------------------------------------

    protected String makeDecision() throws IOException, InterruptedException {
        // Look for a package that has any positive constraint but no decision yet.
        // Iteration order: insertion order of the assignments (stable + deterministic).
        Set<String> seen = new LinkedHashSet<>();
        for (PartialSolution.Assignment a : solution.assignments()) {
            seen.add(a.term().pkg());
        }
        Map<String, String> decided = solution.decisions();

        for (String pkg : seen) {
            if (decided.containsKey(pkg)) continue;
            // Phantom: mentioned only via derived negative incompatibilities.
            // No positive constraint requires it to exist — leave it alone.
            // (Distinct from a real positive-but-unbounded constraint like
            // `pkg@latest`, where `positiveSet` *also* returns ALL but the
            // solver still needs to pick a concrete version.)
            if (!solution.hasPositiveTerm(pkg)) continue;
            VersionSet allowed = solution.positiveSet(pkg);
            if (allowed.isEmpty()) continue;

            String pick = chooseVersion(pkg, allowed);
            if (pick == null) {
                // No available version of pkg satisfies the constraints.
                // Distinguish "package doesn't exist anywhere" (empty version
                // list from the source) from "package exists but no version
                // matches" — the diagnostic renderer needs the difference to
                // surface the artifact-defaulting hint only when the artifact
                // itself wasn't found.
                boolean unknownPackage = source.versions(pkg).isEmpty();
                Incompatibility noVersions = new Incompatibility(
                        List.of(Term.positive(pkg, allowed)),
                        new Incompatibility.Cause.NoVersions(pkg, allowed, unknownPackage));
                addIncompatibility(noVersions);
                // Propagating this inco will surface the conflict on the next loop.
                return pkg;
            }

            solution.decide(pkg, pick);

            // Ingest this version's dependencies as incompatibilities.
            Term decisionTerm = Term.positive(pkg, VersionSet.exact(pick));
            for (Term depTerm : source.dependencies(pkg, pick)) {
                addIncompatibility(new Incompatibility(
                        List.of(decisionTerm, depTerm.invert()),
                        new Incompatibility.Cause.Dependency(decisionTerm, depTerm)));
            }
            return pkg;
        }
        return null; // all packages decided
    }

    private String chooseVersion(String pkg, VersionSet allowed) throws IOException, InterruptedException {
        // versions() is highest-first. Prefer the highest stable version that
        // satisfies the constraint; only fall back to a pre-release when no
        // stable version does (e.g. an explicit `=2.4.0-RC2` pin, a BOM pin, or
        // a package that has only ever published pre-releases). This keeps
        // floating constraints (^/~/ranges/latest) off alphas/betas/RCs/
        // snapshots while still resolving when a pre-release is the only option.
        String prerelease = null;
        for (String version : source.versions(pkg)) {
            if (!allowed.contains(version)) continue;
            if (dev.jkbuild.resolver.Versions.isStable(version)) return version;
            if (prerelease == null) prerelease = version;
        }
        return prerelease;
    }

    // --- helpers -----------------------------------------------------------

    protected void addIncompatibility(Incompatibility inco) {
        incompatibilities.add(inco);
    }

    private static String removeFirst(Set<String> set) {
        var iter = set.iterator();
        String first = iter.next();
        iter.remove();
        return first;
    }
}
