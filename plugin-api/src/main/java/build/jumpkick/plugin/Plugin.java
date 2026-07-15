// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.plugin;

import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.util.List;

/**
 * The jk plugin SPI. A plugin jar exposes exactly one implementation via {@link
 * java.util.ServiceLoader} ({@code META-INF/services/build.jumpkick.plugin.Plugin}).
 *
 * <p>A {@code Plugin} is an {@link Extension} that runs <em>outside</em> the engine JVM: it runs as
 * a managed, sandboxed forked worker JVM ({@code PluginWorkerMain} on the worker's classpath
 * {@code ServiceLoader}-loads the plugin and calls {@link #run}), emitting structured results
 * through the {@link ProtocolWriter} tagged with the plugin's {@link
 * PluginManifest#protocolPrefix()}. (In-engine extensions like the git backend implement
 * {@link Extension} directly instead.)
 */
public interface Plugin extends Extension {

    /** Static identity + protocol prefix. */
    PluginManifest manifest();

    /** An extension's id is its manifest id. */
    @Override
    default String id() {
        return manifest().id();
    }

    /**
     * A plugin's phases are derived from the capability interfaces it implements ({@link
     * build.jumpkick.plugin.build.BuildExtension} ⇒ {@link build.jumpkick.plugin.build.Phase#COMPILE},
     * …); a bare {@link build.jumpkick.plugin.build.BuildPlugin} that only uses {@code register()}
     * declares its phases through its steps' {@code after}/{@code before} instead.
     */
    @Override
    default java.util.Set<build.jumpkick.plugin.build.Phase> phases() {
        return build.jumpkick.plugin.build.Capabilities.phasesOf(this);
    }

    /**
     * Execute the plugin against {@code args} (the verbatim process arguments — typically a single
     * spec-file path), emitting structured results through {@code out}. Return the process exit code
     * (0 = success).
     */
    int run(List<String> args, ProtocolWriter out) throws Exception;
}
