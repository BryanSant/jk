// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import dev.jkbuild.model.Coordinate;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Ordered list of {@link MavenRepo}s with try-each / first-hit-wins semantics per PRD §7.5. Used by
 * {@code EffectivePomBuilder}, {@code MavenPackageSource}, and {@code LockOrchestrator} so they're
 * indifferent to whether the user declared one or many repositories.
 */
public final class RepoGroup {

    private final List<MavenRepo> repos;

    public RepoGroup(List<MavenRepo> repos) {
        Objects.requireNonNull(repos, "repos");
        if (repos.isEmpty()) {
            throw new IllegalArgumentException("RepoGroup must contain at least one repo");
        }
        this.repos = List.copyOf(repos);
    }

    public static RepoGroup of(MavenRepo single) {
        return new RepoGroup(List.of(single));
    }

    public List<MavenRepo> repos() {
        return repos;
    }

    public Optional<RepoFetched> tryFetchPom(Coordinate coord) throws IOException, InterruptedException {
        return tryFetch(coord, MavenRepo::fetchPom);
    }

    public Optional<RepoFetched> tryFetchArtifact(Coordinate coord) throws IOException, InterruptedException {
        return tryFetch(coord, MavenRepo::fetchArtifact);
    }

    public Optional<RepoFetched> tryFetchMetadata(Coordinate coord) throws IOException, InterruptedException {
        return tryFetch(coord, MavenRepo::fetchMetadata);
    }

    /**
     * Union of the versions of {@code coord}'s {@code group:artifact} available across all repos,
     * de-duplicated, preserving first-seen order. Online this merges each repo's {@code
     * maven-metadata.xml}; offline it merges what each repo's journal holds locally.
     */
    public List<String> availableVersions(Coordinate coord) throws IOException, InterruptedException {
        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>();
        for (MavenRepo repo : repos) {
            union.addAll(repo.availableVersions(coord));
        }
        return List.copyOf(union);
    }

    private Optional<RepoFetched> tryFetch(Coordinate coord, Fetcher fetcher) throws IOException, InterruptedException {
        for (MavenRepo repo : repos) {
            try {
                MavenRepo.Fetched f = fetcher.fetch(repo, coord);
                return Optional.of(new RepoFetched(repo, f));
            } catch (MavenRepo.ArtifactNotFoundException ignored) {
                // try next repo
            }
        }
        return Optional.empty();
    }

    public record RepoFetched(MavenRepo repo, MavenRepo.Fetched fetched) {}

    @FunctionalInterface
    private interface Fetcher {
        MavenRepo.Fetched fetch(MavenRepo repo, Coordinate coord) throws IOException, InterruptedException;
    }
}
