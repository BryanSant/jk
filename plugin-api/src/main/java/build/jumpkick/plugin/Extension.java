// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin;

import build.jumpkick.plugin.build.Phase;
import java.util.Set;

/**
 * Anything that extends the core jk engine. An extension MAY run inside the engine JVM (e.g. the
 * git backend, the javac strategy, a local-tool probe) — the sub-interface {@link Plugin} is the
 * specialization that runs <em>outside</em> the engine, in a managed, sandboxed forked worker JVM
 * that communicates over NDJSON.
 *
 * <p>Clients (the CLI, the Web UI) are <em>not</em> extensions even though they speak the same
 * NDJSON protocol — they drive the engine from the outside; they don't extend it.
 *
 * <p>An extension declares which coarse pipeline {@link Phase}(s) it participates in. For in-engine
 * domain extensions (git/compile-strategy/probe) this is metadata the engine reads for ordering and
 * {@code jk explain}; the engine invokes them through their own domain interface. For contributing
 * {@link Plugin}s the phase set is normally derived from the capability interfaces they implement
 * (e.g. {@code BuildExtension} ⇒ {@link Phase#COMPILE}). Zero phases is valid — a standalone command
 * plugin (audit/format/compat) participates in no build phase.
 */
public interface Extension {

    /** Stable identity of this extension (for discovery, ordering, and diagnostics). */
    String id();

    /** The coarse pipeline phases this extension participates in; empty when it maps to none. */
    default Set<Phase> phases() {
        return Set.of();
    }
}
