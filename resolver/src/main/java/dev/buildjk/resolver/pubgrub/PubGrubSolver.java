// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver.pubgrub;

import java.io.IOException;
import java.util.ArrayList;
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
        Term rootTerm = Term.positive(rootPkg, VersionSet.exact(rootVersion));

        // Each declared dep becomes inco [+root@v, ¬dep] — "if root@v then dep must
        // be in its requested set". Per PubGrub paper §3: a dependency inco contains
        // the parent decision positively and the child requirement negatively.
        for (Term dep : rootDeps) {
            addIncompatibility(new Incompatibility(
                    List.of(rootTerm, dep.invert()),
                    new Incompatibility.Cause.Dependency(rootTerm, dep)));
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
     * Stage 2 stub: throw on first real conflict. Stage 3 replaces this
     * with the PubGrub conflict-resolution + backtracking algorithm.
     */
    protected void handleConflict(Incompatibility inco) {
        throw new UnsatisfiableException(inco);
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
            VersionSet allowed = solution.positiveSet(pkg);
            if (allowed.isEmpty() || allowed.isAll()) {
                // ALL = the partial solution mentions this package only via
                // a contradicted term (constraint dissolved). Nothing to do.
                continue;
            }

            String pick = chooseVersion(pkg, allowed);
            if (pick == null) {
                // No available version of pkg satisfies the constraints.
                Incompatibility noVersions = new Incompatibility(
                        List.of(Term.positive(pkg, allowed)),
                        new Incompatibility.Cause.NoVersions(pkg, allowed));
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

    private String chooseVersion(String pkg, VersionSet allowed)
            throws IOException, InterruptedException {
        for (String version : source.versions(pkg)) {
            if (allowed.contains(version)) return version;
        }
        return null;
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
