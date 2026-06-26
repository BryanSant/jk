// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

/**
 * Receives per-package events during {@link LockOrchestrator#lock}. Implementations are called on
 * the resolver thread — must be thread-safe.
 */
public interface ResolveObserver {
    /** Called once when the total package count is known (after the solver returns). */
    void onTotal(int total);

    /** Called for each package as it is fetched and recorded in the lockfile. */
    void onPackage(String module, String version);

    /** No-op observer — used when no progress tracking is needed. */
    ResolveObserver NOOP = new ResolveObserver() {
        @Override
        public void onTotal(int total) {}

        @Override
        public void onPackage(String module, String version) {}
    };
}
