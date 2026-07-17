// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.build;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * The default dependency graph over the coarse {@link Phase}s — the single source of truth for
 * "which phases does a target need, and in what order":
 *
 * <pre>
 *   resolve → compile → test → package → { run | image | publish },   image → publish
 * </pre>
 *
 * <p>This is <em>coarse ordering only</em>. The real execution DAG stays at the fine {@link
 * cc.jumpkick.run.Step} level ({@code Step.requires}); the engine maps each plugin step's
 * {@code after}/{@code before} {@link Phase} onto concrete step edges. This graph lets callers
 * resolve a target's minimal phase set and validate that a declared phase window is coherent,
 * without hard-coding the order in each place.
 *
 * <p>Terminal goals ({@code run}/{@code image}/{@code publish}) are alternatives after {@code
 * package}; {@code image → publish} is an allowed chain (publishing an image), not a requirement
 * (a plain artifact publishes without one), so {@code publish}'s default prerequisite is {@code
 * package}.
 */
public final class PhaseGraph {

    private PhaseGraph() {}

    /** Direct prerequisite phases of each phase (its immediate upstream edges). */
    private static final Map<Phase, Set<Phase>> UPSTREAM = upstream();

    private static Map<Phase, Set<Phase>> upstream() {
        Map<Phase, Set<Phase>> m = new EnumMap<>(Phase.class);
        m.put(Phase.RESOLVE, EnumSet.noneOf(Phase.class));
        m.put(Phase.COMPILE, EnumSet.of(Phase.RESOLVE));
        m.put(Phase.TEST, EnumSet.of(Phase.COMPILE));
        m.put(Phase.PACKAGE, EnumSet.of(Phase.TEST));
        m.put(Phase.RUN, EnumSet.of(Phase.PACKAGE));
        m.put(Phase.IMAGE, EnumSet.of(Phase.PACKAGE));
        m.put(Phase.PUBLISH, EnumSet.of(Phase.PACKAGE));
        return m;
    }

    /** The direct prerequisite phases of {@code phase} (empty for {@link Phase#RESOLVE}). */
    public static Set<Phase> upstreamOf(Phase phase) {
        return Collections.unmodifiableSet(UPSTREAM.getOrDefault(phase, EnumSet.noneOf(Phase.class)));
    }

    /** The transitive prerequisite closure of {@code phase}, inclusive of {@code phase} itself. */
    public static Set<Phase> closure(Phase phase) {
        EnumSet<Phase> out = EnumSet.noneOf(Phase.class);
        collect(phase, out);
        return out;
    }

    private static void collect(Phase phase, EnumSet<Phase> out) {
        if (!out.add(phase)) return;
        for (Phase up : UPSTREAM.getOrDefault(phase, EnumSet.noneOf(Phase.class))) collect(up, out);
    }

    /** True when {@code a} is a (transitive) prerequisite of {@code b} — i.e. {@code a} runs before {@code b}. */
    public static boolean precedes(Phase a, Phase b) {
        return a != b && closure(b).contains(a);
    }

    /**
     * A coherent {@code after}/{@code before} window for a step: {@code after} must run no later than
     * {@code before} — either the same phase, or a prerequisite of it. Rejects reversed windows
     * (e.g. after PACKAGE, before COMPILE).
     */
    public static boolean isValidWindow(Phase after, Phase before) {
        return after == before || precedes(after, before);
    }

    /**
     * The minimal set of phases a target terminating at {@code terminal} must run. When
     * {@code includeTest} is false the (skippable) {@link Phase#TEST} gate is dropped — unless the
     * target <em>is</em> test.
     */
    public static Set<Phase> closureFor(Phase terminal, boolean includeTest) {
        EnumSet<Phase> out = EnumSet.copyOf(closure(terminal));
        if (!includeTest && terminal != Phase.TEST) out.remove(Phase.TEST);
        return out;
    }
}
