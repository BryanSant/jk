// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver.pubgrub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Solver state: the ordered sequence of assignments that the solver has committed to so far.
 * Maintains the decision level (the depth in the search tree) so we can backtrack on conflict.
 *
 * <p>Each {@link Assignment} is either a {@link Assignment.Decision} (the solver chose a specific
 * version) or a {@link Assignment.Derivation} (a term was forced by an incompatibility under unit
 * propagation).
 */
public final class PartialSolution {

    private final List<Assignment> assignments = new ArrayList<>();
    private final Map<String, VersionSet> positiveByPackage = new HashMap<>();

    /**
     * Packages that the partial solution has seen via at least one <em>positive</em> term. Used to
     * distinguish "needs a decision" (positive constraint, even if the constraint set happens to be
     * {@link VersionSet#ALL}) from a phantom package mentioned only by derived negative
     * incompatibilities (no positive force ever required it; the solver shouldn't pick a version for
     * it).
     */
    private final Set<String> packagesWithPositiveTerm = new HashSet<>();

    /**
     * Packages mentioned by ANY assignment, positive or negative. {@link #contradicts} must apply
     * real set logic as soon as a package is constrained at all — a package narrowed only by
     * negative derivations (e.g. {@code ¬engine [6.1.1,+∞)} from a NoVersions conflict) is
     * genuinely contradicted for that range, and treating it as "unconstrained" made the same
     * single-term incompatibility ALMOST_SATISFIED forever (an infinite derive loop that OOMed
     * the resolver on real Boot graphs).
     */
    private final Set<String> mentionedPackages = new HashSet<>();

    private final Map<String, String> decisionByPackage = new TreeMap<>();
    private int decisionLevel = 0;

    public sealed interface Assignment {
        Term term();

        int decisionLevel();

        int globalIndex();

        record Decision(Term term, int decisionLevel, int globalIndex) implements Assignment {}

        record Derivation(Term term, int decisionLevel, int globalIndex, Incompatibility cause) implements Assignment {}
    }

    public void decide(String pkg, String version) {
        decisionLevel++;
        Term decision = Term.positive(pkg, VersionSet.exact(version));
        assignments.add(new Assignment.Decision(decision, decisionLevel, assignments.size()));
        decisionByPackage.put(pkg, version);
        register(decision);
    }

    public void derive(Term term, Incompatibility cause) {
        assignments.add(new Assignment.Derivation(term, decisionLevel, assignments.size(), cause));
        register(term);
    }

    private void register(Term t) {
        positiveByPackage.merge(t.pkg(), t.effectiveVersions(), VersionSet::intersect);
        mentionedPackages.add(t.pkg());
        if (t.positive()) {
            packagesWithPositiveTerm.add(t.pkg());
        }
    }

    /**
     * True iff at least one positive term about {@code pkg} has been recorded — i.e. the partial
     * solution requires the package to exist at some version. False for "phantom" packages mentioned
     * only by negative incompatibilities.
     */
    public boolean hasPositiveTerm(String pkg) {
        return packagesWithPositiveTerm.contains(pkg);
    }

    public int decisionLevel() {
        return decisionLevel;
    }

    /** Intersection of all term constraints known so far for {@code pkg}. */
    public VersionSet positiveSet(String pkg) {
        return positiveByPackage.getOrDefault(pkg, VersionSet.ALL);
    }

    /** True iff every version still allowed for {@code term.pkg()} satisfies {@code term}. */
    public boolean satisfies(Term term) {
        return positiveSet(term.pkg()).subsetOf(term.effectiveVersions());
    }

    /**
     * True iff every allowed version of {@code term.pkg()} contradicts {@code term}.
     *
     * <p>If no assignment mentions {@code term.pkg()} at all, the package is unconstrained —
     * nothing the solution holds can contradict a term about it. Without this guard, a negative
     * term whose {@code effectiveVersions} is {@link VersionSet#EMPTY} (the negation of a positive
     * root term with {@code versionSet = ALL}, i.e. an unbounded {@code @latest} selector) would
     * spuriously appear contradicted — and the inco carrying it would stay INCONCLUSIVE forever,
     * so no positive term for the package ever gets derived and the dep is silently dropped from
     * the resolution. The guard is {@link #mentionedPackages}, NOT {@link
     * #packagesWithPositiveTerm}: once even a negative derivation narrows the package, real set
     * logic must apply (see the field docs for the infinite-derive failure otherwise).
     */
    public boolean contradicts(Term term) {
        if (!mentionedPackages.contains(term.pkg())) return false;
        return positiveSet(term.pkg()).intersect(term.effectiveVersions()).isEmpty();
    }

    /** Snapshot of all packages with finalized decisions. */
    public Map<String, String> decisions() {
        return new TreeMap<>(decisionByPackage);
    }

    public List<Assignment> assignments() {
        return List.copyOf(assignments);
    }

    /**
     * Discard every assignment with decision level &gt; {@code targetLevel} and replay the survivors.
     * Used by conflict resolution.
     */
    public void backtrack(int targetLevel) {
        if (targetLevel < 0) {
            throw new IllegalArgumentException("cannot backtrack below 0: " + targetLevel);
        }
        assignments.removeIf(a -> a.decisionLevel() > targetLevel);
        decisionLevel = targetLevel;
        decisionByPackage.clear();
        positiveByPackage.clear();
        packagesWithPositiveTerm.clear();
        mentionedPackages.clear();
        for (Assignment a : assignments) {
            register(a.term());
            if (a instanceof Assignment.Decision d
                    && d.term().effectiveVersions() instanceof VersionSet.Range r
                    && r.min() != null
                    && r.minInclusive()
                    && r.maxInclusive()) {
                decisionByPackage.put(d.term().pkg(), r.min());
            }
        }
    }

    /**
     * Compute the relation between an {@link Incompatibility} and the current partial solution.
     * Distinguishes the three states PubGrub acts on (paper §3 / §4):
     *
     * <ul>
     *   <li>{@link IncompatibilityRelation#SATISFIED}: every term holds — a real conflict, must be
     *       resolved.
     *   <li>{@link IncompatibilityRelation#ALMOST_SATISFIED}: exactly one term doesn't hold, the rest
     *       do — unit propagation fires.
     *   <li>{@link IncompatibilityRelation#INCONCLUSIVE}: more than one term unresolved — nothing to
     *       do yet.
     * </ul>
     */
    public Relation relationTo(Incompatibility inco) {
        Term unsatisfied = null;
        int unsatisfiedCount = 0;
        for (Term term : inco.terms()) {
            if (contradicts(term)) {
                return new Relation(IncompatibilityRelation.INCONCLUSIVE, null);
            }
            if (!satisfies(term)) {
                unsatisfied = term;
                unsatisfiedCount++;
                if (unsatisfiedCount > 1) {
                    return new Relation(IncompatibilityRelation.INCONCLUSIVE, null);
                }
            }
        }
        if (unsatisfied == null) {
            return new Relation(IncompatibilityRelation.SATISFIED, null);
        }
        return new Relation(IncompatibilityRelation.ALMOST_SATISFIED, unsatisfied);
    }

    public enum IncompatibilityRelation {
        SATISFIED,
        ALMOST_SATISFIED,
        INCONCLUSIVE
    }

    public record Relation(IncompatibilityRelation kind, Term unsatisfied) {}
}
