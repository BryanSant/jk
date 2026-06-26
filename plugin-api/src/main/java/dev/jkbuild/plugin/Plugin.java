// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

import dev.jkbuild.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The jk plugin SPI. A plugin jar exposes exactly one implementation via
 * {@link java.util.ServiceLoader} ({@code META-INF/services/dev.jkbuild.plugin.Plugin}).
 *
 * <p>Every plugin runs as a forked worker JVM: {@code PluginWorkerMain} on the
 * worker's classpath {@code ServiceLoader}-loads the plugin and calls
 * {@link #run}, which emits structured results through the {@link ProtocolWriter}
 * tagged with the plugin's {@link PluginManifest#protocolPrefix()}.
 */
public interface Plugin {

    /** Static identity + protocol prefix. */
    PluginManifest manifest();

    /**
     * Execute the plugin against {@code args} (the verbatim process arguments —
     * typically a single spec-file path), emitting structured results through
     * {@code out}. Return the process exit code (0 = success).
     */
    int run(List<String> args, ProtocolWriter out) throws Exception;
}
