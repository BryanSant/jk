// SPDX-License-Identifier: Apache-2.0
/**
 * The engine's optional embedded HTTP server — see {@code docs/http.md}. Enabled only by the
 * presence of an {@code [http]} table in {@code ~/.jk/config.toml}
 * ({@link dev.jkbuild.config.JkHttpConfig}); when absent, nothing in this package is constructed.
 *
 * <p><strong>Handlers must be IO-shaped.</strong> Requests run on virtual threads, which share the
 * JVM-global carrier pool (sized to the core count, no time-sliced preemption) with the engine's
 * connection and build threads — a CPU-bound handler pins a carrier until it blocks. Any endpoint
 * that computes (stats aggregation, report rendering) must submit that work to {@link
 * dev.jkbuild.run.JkThreads#cpu()} and await it: the virtual thread unmounts while waiting, and
 * the CPU work competes inside the same bounded platform pool the engine already budgets for
 * hashing instead of against the carriers. This is the review bar for every new endpoint.
 *
 * <p>The server is advisory, never load-bearing: a bind failure is logged and surfaced in {@code jk
 * engine status}, and the engine serves builds without it.
 */
package dev.jkbuild.engine.http;
