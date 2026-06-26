// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.forge;

/**
 * A forge provider + host inferred from a repository's git remote. Produced by
 * {@link GitForgeDetector}. {@code host} is the real host after any
 * {@code ~/.ssh/config} alias has been resolved (so it's a value
 * {@link ForgeKind#inferFromHost} recognised).
 */
public record ForgeRemote(ForgeKind kind, String host) {}
