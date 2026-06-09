// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

import dev.jkbuild.plugin.protocol.ProtocolWriter;

import java.util.List;

/**
 * The jk plugin SPI. A plugin jar exposes exactly one implementation via
 * {@link java.util.ServiceLoader} ({@code META-INF/services/dev.jkbuild.plugin.Plugin});
 * the shared {@link dev.jkbuild.plugin.host.PluginHostMain} discovers it, builds
 * a {@link ProtocolWriter} from {@link #manifest()}, and invokes {@link #run}.
 *
 * <p>This is the worker-side seam: today a plugin runs as a forked JVM driven
 * over stdio (replacing the hand-rolled per-runner {@code main()}s). When the
 * Workspace Host lands (docs/plugin-refactor.md §3), the same {@code Plugin}
 * will be loadable in-process via an isolated classloader, and {@link #run}'s
 * parameters will widen into a richer {@code PluginContext}/{@code Services}
 * surface. Keeping {@code run} narrow now (args + writer) avoids committing to
 * that surface before the Host defines it.
 */
public interface Plugin {

    /** Static identity + protocol prefix. */
    PluginManifest manifest();

    /**
     * Execute the plugin against {@code args} (the verbatim process arguments —
     * typically a single spec-file path), emitting structured results through
     * {@code out}. Return the process exit code (0 = success). Diagnostics that
     * are not protocol lines may be written to {@code System.err}.
     */
    int run(List<String> args, ProtocolWriter out) throws Exception;
}
