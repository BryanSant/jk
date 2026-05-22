// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.resolver;

import dev.buildjk.cache.Cas;
import dev.buildjk.http.Http;
import dev.buildjk.lock.Lockfile;
import dev.buildjk.model.Coordinate;
import dev.buildjk.repo.MavenRepo;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reconciles the on-disk content-addressed cache against a {@link Lockfile}.
 * For each locked package with a recorded sha256, ensures the blob is in
 * the CAS — downloading from the source URL recorded in the lockfile entry
 * if missing. Verifies the downloaded sha256 matches what the lockfile
 * promised (mismatches are reported, never silently accepted).
 */
public final class CacheSync {

    private final Cas cas;
    private final Http http;

    public CacheSync(Cas cas, Http http) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.http = Objects.requireNonNull(http, "http");
    }

    public Report sync(Lockfile lock) throws IOException, InterruptedException {
        int fetched = 0;
        int upToDate = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        Map<String, MavenRepo> repoCache = new HashMap<>();

        for (Lockfile.Package pkg : lock.packages()) {
            if (pkg.checksum() == null) {
                skipped++; // POM-only / path / git deps
                continue;
            }
            String hex = pkg.checksum().startsWith("sha256:")
                    ? pkg.checksum().substring("sha256:".length())
                    : pkg.checksum();

            if (cas.contains(hex)) {
                upToDate++;
                continue;
            }

            try {
                MavenRepo repo = repoFor(pkg.source(), repoCache);
                Coordinate coord = toCoord(pkg);
                MavenRepo.Fetched f = repo.fetchArtifact(coord);
                if (!f.sha256().equals(hex)) {
                    errors.add(pkg.name() + " v" + pkg.version()
                            + ": checksum mismatch — lock says " + hex + ", got " + f.sha256());
                } else {
                    fetched++;
                }
            } catch (IOException e) {
                errors.add(pkg.name() + " v" + pkg.version() + ": " + e.getMessage());
            }
        }

        return new Report(fetched, upToDate, skipped, List.copyOf(errors));
    }

    private MavenRepo repoFor(String source, Map<String, MavenRepo> cache) {
        MavenRepo existing = cache.get(source);
        if (existing != null) return existing;
        int plus = source.indexOf('+');
        if (plus <= 0 || plus >= source.length() - 1) {
            throw new IllegalArgumentException(
                    "lockfile package source must be '<name>+<url>', got: " + source);
        }
        String name = source.substring(0, plus);
        URI url = URI.create(source.substring(plus + 1));
        MavenRepo repo = new MavenRepo(name, url, http, cas);
        cache.put(source, repo);
        return repo;
    }

    private static Coordinate toCoord(Lockfile.Package pkg) {
        int colon = pkg.name().indexOf(':');
        return Coordinate.of(
                pkg.name().substring(0, colon),
                pkg.name().substring(colon + 1),
                pkg.version());
    }

    public record Report(int fetched, int upToDate, int skipped, List<String> errors) {
        public Report {
            errors = List.copyOf(errors);
        }
        public boolean hasErrors() { return !errors.isEmpty(); }
    }
}
