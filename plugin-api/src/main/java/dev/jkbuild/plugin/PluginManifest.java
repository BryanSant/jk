// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

/**
 * Static identity a {@link Plugin} declares to the host.
 *
 * @param id             stable plugin id (e.g. {@code "jk-git-runner"})
 * @param protocolPrefix marker each protocol line carries (e.g. {@code "##JKGIT:"})
 * @param isolation      execution model: {@code "in-process"} (default — isolated
 *                       {@link java.net.URLClassLoader}) or {@code "process"} (fork
 *                       a child JVM via stdio, for plugins with hostile classpaths).
 */
public record PluginManifest(String id, String protocolPrefix, String isolation) {

    /** Convenience constructor: defaults to in-process isolation. */
    public PluginManifest(String id, String protocolPrefix) {
        this(id, protocolPrefix, "in-process");
    }

    /** True when this plugin should run in the same JVM as the Host. */
    public boolean inProcess() {
        return !"process".equalsIgnoreCase(isolation);
    }
}
