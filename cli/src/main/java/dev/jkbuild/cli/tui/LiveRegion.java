// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.tui;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A live, in-place terminal region — a {@link SpinnerProgressBar}, a
 * {@link Spinner}-backed display, or the multi-line goal view — that owns part
 * of the screen between "show" and "close".
 *
 * <p>At most one region is on screen at a time; it registers itself via
 * {@link #setActive} and clears via {@link #clearActive}. The app-level SIGINT
 * handler ({@link GlobalCancel}) repaints {@link #active()} as canceled before
 * halting, so whatever the user was watching is left in a clean, clearly
 * canceled state rather than frozen mid-frame.
 */
public interface LiveRegion {

    /**
     * Repaint this region in its canceled state. Invoked from the Ctrl-C
     * handler thread, so implementations must be safe to call concurrently
     * with their own rendering (today they all {@code synchronized}).
     */
    void renderCanceled();

    /** Register {@code region} as the region currently on screen. */
    static void setActive(LiveRegion region) {
        Holder.ACTIVE.set(region);
    }

    /** Clear {@code region} from the active slot iff it is still the active one. */
    static void clearActive(LiveRegion region) {
        Holder.ACTIVE.compareAndSet(region, null);
    }

    /** @return the region currently on screen, or {@code null} if none. */
    static LiveRegion active() {
        return Holder.ACTIVE.get();
    }

    /** Encapsulates the mutable registry so the interface exposes only methods. */
    final class Holder {
        private Holder() {}
        private static final AtomicReference<LiveRegion> ACTIVE = new AtomicReference<>();
    }
}
