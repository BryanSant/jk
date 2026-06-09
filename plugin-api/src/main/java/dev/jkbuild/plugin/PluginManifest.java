// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

/**
 * Static identity a {@link Plugin} declares to the host.
 *
 * <p>Minimal for now — id + the wire-protocol prefix the plugin's
 * {@link dev.jkbuild.plugin.protocol.ProtocolWriter} emits under. Richer fields
 * (version, declared capabilities, isolation hint) arrive with the Workspace
 * Host (see docs/plugin-refactor.md §3.3/§3.8); this record is the seam they
 * will hang off.
 *
 * @param id             stable plugin id (e.g. {@code "jk-git-runner"})
 * @param protocolPrefix marker each protocol line carries (e.g. {@code "##JKGIT:"})
 */
public record PluginManifest(String id, String protocolPrefix) {}
