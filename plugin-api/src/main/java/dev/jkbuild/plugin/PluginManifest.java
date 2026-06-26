// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin;

/**
 * Static identity a {@link Plugin} declares to the host.
 *
 * @param id stable plugin id (e.g. {@code "jk-git-client"})
 * @param protocolPrefix marker each protocol line carries (e.g. {@code "##JKGIT:"}); the dispatcher
 *     listens for this prefix to demux protocol lines from passthrough output.
 */
public record PluginManifest(String id, String protocolPrefix) {}
