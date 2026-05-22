// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.model;

import java.util.Objects;

/**
 * A declared dependency in {@code build.jk}. v0.1 shape: module
 * ({@code group:artifact}) plus version selector.
 *
 * <p>Future fields (from-repo pin, classifier, features, target predicate,
 * git source) join as their respective subsystems come online.
 */
public record Dependency(String module, VersionSelector version) {

    public Dependency {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(version, "version");
        if (!module.contains(":") || module.indexOf(':') != module.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "dependency module must be 'group:artifact' (got: " + module + ")");
        }
    }

    public String group() {
        return module.substring(0, module.indexOf(':'));
    }

    public String artifact() {
        return module.substring(module.indexOf(':') + 1);
    }
}
