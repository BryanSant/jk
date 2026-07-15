// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.model;

import java.util.Objects;

/**
 * A local-path dependency declaration — a sibling project on disk consumed as a <em>dependency</em>
 * (built compile/package-only, never tested), as distinct from a {@code [workspace] modules} entry
 * (built fully, with tests, and jk-only).
 *
 * <p>{@code rawPath} is exactly what the user wrote ({@code "./libs/util"}, {@code "../shared"}, an
 * absolute path, …). It is resolved against the directory of the consuming {@code jk.toml} at
 * materialization time. Like {@link GitSource}, this record carries no coordinate or version: the
 * target's group/artifact/version are discovered when it is built — from its {@code jk.toml} for a
 * jk project, or from the derived GAV for a foreign (Gradle/Maven) project.
 */
public record PathSource(String rawPath) {

    public PathSource {
        Objects.requireNonNull(rawPath, "rawPath");
        if (rawPath.isBlank()) {
            throw new IllegalArgumentException("path dependency rawPath must not be blank");
        }
    }
}
