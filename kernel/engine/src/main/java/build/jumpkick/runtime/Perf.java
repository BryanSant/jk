// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

/**
 * Dev-only wall-clock probe, enabled by {@code JK_PERF=1} in the environment; writes {@code
 * [jk-perf] <label> <ms>} lines to stderr. Zero overhead when disabled (a single static boolean).
 * Temporary diagnostic scaffolding for build-latency work — keep call sites coarse (one per
 * pipeline stage), never per-file.
 */
final class Perf {

    static final boolean ENABLED = System.getenv("JK_PERF") != null;

    private Perf() {}

    static long start() {
        return ENABLED ? System.nanoTime() : 0;
    }

    static void end(String label, long startNanos) {
        if (!ENABLED) return;
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        System.err.println("[jk-perf] " + label + " " + ms + "ms");
    }
}
