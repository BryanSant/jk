// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command.ide;

/**
 * A cross-module (workspace-sibling) dependency edge.
 *
 * @param name the sibling module's IDE name
 * @param scope {@code "COMPILE"} or {@code "TEST"}
 */
public record ModuleRef(String name, String scope) {}
