// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.HostRateLimiter;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.util.JkThreads;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Reconciles the on-disk content-addressed cache against a {@link Lockfile}.
 * For each locked package with a recorded sha256, ensures the blob is in
 * the CAS — downloading from the source URL recorded in the lockfile entry
 * if missing. Verifies the downloaded sha256 matches what the lockfile
 * promised (mismatches are reported, never silently accepted).
 *
 * <p>Fetches run concurrently on {@link JkThreads#io()} with per-host
 * concurrency capped by {@link HostRateLimiter}, so a cold cache against
 * Maven Central populates in parallel without getting throttled.
 */
public final class CacheSync {

    private final Cas cas;
    private final Http http;

    public CacheSync(Cas cas, Http http) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.http = Objects.requireNonNull(http, "http");
    }

    public Report sync(Lockfile lock) throws IOException, InterruptedException {
        int upToDate = 0;
        int skipped = 0;
        // Build the list of fetches to do, resolving repos up front (the
        // repoCache HashMap below isn't thread-safe; precomputing keeps it
        // single-writer).
        Map<String, MavenRepo> repoCache = new HashMap<>();
        List<PendingFetch> pending = new ArrayList<>();
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
            pending.add(new PendingFetch(pkg, hex, repoFor(pkg.source(), repoCache)));
        }

        // Dispatch all fetches concurrently. HostRateLimiter caps per-host
        // concurrency so Maven Central doesn't throttle us.
        HostRateLimiter limiter = HostRateLimiter.shared();
        List<CompletableFuture<FetchResult>> futures = new ArrayList<>(pending.size());
        for (PendingFetch p : pending) {
            futures.add(CompletableFuture.supplyAsync(() -> fetch(p, limiter), JkThreads.io()));
        }

        int fetched = 0;
        List<String> errors = new ArrayList<>();
        for (CompletableFuture<FetchResult> f : futures) {
            FetchResult r;
            try {
                r = f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                // Unwrap unexpected throwables from supplyAsync.
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(cause.getMessage());
                continue;
            }
            if (r.error != null) errors.add(r.error);
            else fetched++;
        }

        return new Report(fetched, upToDate, skipped, List.copyOf(errors));
    }

    private static FetchResult fetch(PendingFetch p, HostRateLimiter limiter) {
        Coordinate coord = toCoord(p.pkg);
        try {
            URI host = p.repo.baseUrl();
            MavenRepo.Fetched f = limiter.run(host, () -> p.repo.fetchArtifact(coord));
            if (!f.sha256().equals(p.expectedHex)) {
                return FetchResult.failure(p.pkg.name() + " v" + p.pkg.version()
                        + ": checksum mismatch — lock says " + p.expectedHex
                        + ", got " + f.sha256());
            }
            return FetchResult.ok();
        } catch (IOException e) {
            return FetchResult.failure(p.pkg.name() + " v" + p.pkg.version() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failure(p.pkg.name() + " v" + p.pkg.version() + ": interrupted");
        }
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

    /** A package whose CAS entry is missing and needs to be fetched. */
    private record PendingFetch(Lockfile.Package pkg, String expectedHex, MavenRepo repo) {}

    /** Outcome of one parallel fetch — null error means success. */
    private record FetchResult(String error) {
        static FetchResult ok() { return new FetchResult(null); }
        static FetchResult failure(String msg) { return new FetchResult(msg); }
    }

    public record Report(int fetched, int upToDate, int skipped, List<String> errors) {
        public Report {
            errors = List.copyOf(errors);
        }
        public boolean hasErrors() { return !errors.isEmpty(); }
    }
}
