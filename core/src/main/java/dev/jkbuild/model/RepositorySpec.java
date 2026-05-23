// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import java.net.URI;
import java.util.Objects;

/**
 * A repository declared in {@code jk.toml}'s {@code repositories.*} block.
 * v0.1 captures only name + URL — auth, mirror, snapshots, etc. arrive
 * with the corresponding {@link dev.jkbuild.repo} subsystems.
 */
public record RepositorySpec(String name, URI url) {

    public static final RepositorySpec MAVEN_CENTRAL = new RepositorySpec(
            "central", URI.create("https://repo.maven.apache.org/maven2/"));

    public RepositorySpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        if (name.isBlank()) throw new IllegalArgumentException("repo name must not be blank");
    }
}
