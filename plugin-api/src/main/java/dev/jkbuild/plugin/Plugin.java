// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

import dev.jkbuild.plugin.protocol.ProtocolWriter;

import java.util.List;

/**
 * The jk plugin SPI. A plugin jar exposes exactly one implementation via
 * {@link java.util.ServiceLoader} ({@code META-INF/services/dev.jkbuild.plugin.Plugin});
 * the Host discovers it and drives it through either {@link #register} (in-process
 * phase-contribution model) or {@link #run} (worker / process-isolated model).
 *
 * <p><b>Phase-contribution model</b> ({@link PluginManifest#inProcess()} true): the
 * Host calls {@link #register} inside an isolated {@code URLClassLoader}. The plugin
 * inspects {@link PluginContext#project()} and calls {@link PluginContext#contribute}
 * for each {@link dev.jkbuild.run.Phase} it wants to add to the build DAG.
 *
 * <p><b>Worker model</b> ({@code isolation = "process"}): the Host forks the plugin
 * jar as a child JVM and communicates over the {@code ##JKH:} stdio protocol.
 * Override {@link #run} only; {@link #register} is a no-op by default.
 */
public interface Plugin {

    /** Static identity + protocol prefix. */
    PluginManifest manifest();

    /**
     * Register phases with the current build. Called in-process by the Host
     * before the Goal is finalized. The default implementation is a no-op —
     * worker-style plugins (that override {@link #run}) need not override this.
     */
    default void register(PluginContext ctx) throws Exception {}

    /**
     * Execute the plugin against {@code args} (the verbatim process arguments —
     * typically a single spec-file path), emitting structured results through
     * {@code out}. Return the process exit code (0 = success). Used for
     * worker / process-isolated plugins; in-process plugins use {@link #register}.
     */
    default int run(List<String> args, ProtocolWriter out) throws Exception {
        return 0;
    }
}
