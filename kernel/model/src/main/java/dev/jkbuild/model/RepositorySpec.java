// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.model;

import dev.jkbuild.credential.RepoCredential;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * A repository declared in {@code jk.toml}'s {@code repositories.*} block:
 * a name, a URL, an optional inline {@link RepoCredential} (from
 * {@code username}/{@code password}/{@code token} fields), and optional
 * {@link ObjectStoreConfig} for {@code s3://}/{@code gs://} backends
 * (region/endpoint/keys) — see docs/artifact-repos.md. Most repos carry
 * neither and resolve auth from env / store / settings.xml / the forge bridge.
 */
public record RepositorySpec(String name, URI url, Optional<RepoCredential> credential,
                             Optional<ObjectStoreConfig> objectStore) {

    public static final RepositorySpec MAVEN_CENTRAL = new RepositorySpec(
            "central", URI.create("https://repo.maven.apache.org/maven2/"));

    /** Convenience: a repository with no inline credential or object-store config. */
    public RepositorySpec(String name, URI url) {
        this(name, url, Optional.empty(), Optional.empty());
    }

    /** Convenience: a repository with a credential but no object-store config. */
    public RepositorySpec(String name, URI url, Optional<RepoCredential> credential) {
        this(name, url, credential, Optional.empty());
    }

    public RepositorySpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(objectStore, "objectStore");
        if (name.isBlank()) throw new IllegalArgumentException("repo name must not be blank");
    }
}
