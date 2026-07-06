// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.resolver;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.http.HostRateLimiter;
import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.repo.MavenRepo;
import dev.jkbuild.repo.RepoArtifactResolver;
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
 * Reconciles the on-disk content-addressed cache against a {@link Lockfile}. For each locked
 * package with a recorded sha256, ensures the blob is in the CAS — downloading from the source URL
 * recorded in the lockfile entry if missing. Verifies the downloaded sha256 matches what the
 * lockfile promised (mismatches are reported, never silently accepted).
 *
 * <p>Fetches run concurrently on {@link JkThreads#io()} with per-host concurrency capped by {@link
 * HostRateLimiter}, so a cold cache against Maven Central populates in parallel without getting
 * throttled.
 */
public final class CacheSync {

    private final Cas cas;
    private final Http http;
    private final dev.jkbuild.repo.RepoCredentialResolver creds;

    public CacheSync(Cas cas, Http http) {
        this(cas, http, new dev.jkbuild.repo.RepoCredentialResolver());
    }

    /** Visible for tests — inject a credential resolver. */
    public CacheSync(Cas cas, Http http, dev.jkbuild.repo.RepoCredentialResolver creds) {
        this.cas = Objects.requireNonNull(cas, "cas");
        this.http = Objects.requireNonNull(http, "http");
        this.creds = Objects.requireNonNull(creds, "creds");
    }

    /**
     * Counts the artifacts in {@code lock} that will be processed by {@link #sync} — i.e.,
     * those with a non-null checksum (POM-only, path, and git deps are skipped). Used to
     * pre-compute the bar denominator before the sync starts.
     */
    public static int countArtifacts(Lockfile lock) {
        int count = 0;
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() != null) count++;
        }
        return count;
    }

    public Report sync(Lockfile lock) throws IOException, InterruptedException {
        return sync(lock, ProgressObserver.NOOP, false);
    }

    public Report sync(Lockfile lock, ProgressObserver observer) throws IOException, InterruptedException {
        return sync(lock, observer, false);
    }

    /**
     * Sync with per-package progress reporting. {@code observer} is notified as each package's
     * outcome resolves — synchronously for already-cached / POM-only packages, on the fetcher's
     * completion thread for fetched / failed packages. Use this to drive a Goal-style progress bar
     * where the numerator climbs one tick per package processed.
     *
     * <p>When {@code refresh} is true (set by {@code --force}) the CAS presence check is skipped
     * and every artifact is re-downloaded from its source, regardless of whether a local copy
     * already exists.
     */
    public Report sync(Lockfile lock, ProgressObserver observer, boolean refresh)
            throws IOException, InterruptedException {
        int upToDate = 0;
        int skipped = 0;
        // Build the list of fetches to do, resolving repos up front (the
        // repoCache HashMap below isn't thread-safe; precomputing keeps it
        // single-writer).
        Map<String, MavenRepo> repoCache = new HashMap<>();
        List<PendingFetch> pending = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.checksum() == null) {
                skipped++; // POM-only / path / git deps
                observer.skipped(pkg);
                continue;
            }
            String hex = pkg.checksumHex();

            // Check the named-repo store first (repos/<name>/<m2-path>.sha256) — a stat call
            // that works cross-project without any CAS knowledge.  Fall back to the CAS for
            // artifacts fetched before this store existed (old lockfiles / old builds).
            if (!refresh && (repoStoreContains(pkg) || cas.contains(hex))) {
                upToDate++;
                observer.upToDate(pkg);
                continue;
            }
            // A "local" source (jk install <file>, jk's own worker JARs) is never fetched from a
            // remote repo — it lives in the repos/local full store. Materialize it into the CAS so
            // the compile classpath can resolve it by hash, then treat it as satisfied.
            if ("local".equals(pkg.source())) {
                if (materializeLocal(pkg, hex)) upToDate++;
                else skipped++;
                observer.upToDate(pkg);
                continue;
            }
            pending.add(new PendingFetch(pkg, hex, repoFor(pkg.source(), repoCache)));
        }

        // Dispatch all fetches concurrently. HostRateLimiter caps per-host
        // concurrency so Maven Central doesn't throttle us.
        HostRateLimiter limiter = HostRateLimiter.shared();
        List<CompletableFuture<FetchResult>> futures = new ArrayList<>(pending.size());
        for (PendingFetch p : pending) {
            CompletableFuture<FetchResult> fut = CompletableFuture.supplyAsync(() -> fetch(p, limiter), JkThreads.io());
            // Fire the per-package callback on the fetcher's completion
            // thread so the progress bar updates as parallel fetches
            // finish, not in a single end-of-pass burst. thenAccept
            // returns a new future we don't track — the original `fut`
            // is what we collect below; the notification side-effect
            // happens before the parent join.
            fut.thenAccept(result -> {
                if (result.error != null) observer.failed(p.pkg, result.error);
                else observer.fetched(p.pkg);
            });
            futures.add(fut);
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

    /**
     * Fetch sources JARs for every locked package that has a {@code sources-checksum} field.
     * Already-cached sources are skipped. Packages without a sources checksum are silently ignored.
     *
     * @return count of sources JARs fetched (not counting those already cached)
     */
    public int syncSources(Lockfile lock, ProgressObserver observer) throws IOException, InterruptedException {
        Map<String, MavenRepo> repoCache = new HashMap<>();
        List<PendingFetch> pending = new ArrayList<>();
        for (Lockfile.Artifact pkg : lock.artifacts()) {
            if (pkg.sourcesChecksum() == null) continue;
            String hex = pkg.sourcesChecksumHex();
            if (cas.contains(hex)) {
                observer.upToDate(pkg);
                continue;
            }
            // Reuse the existing repoFor() with the package's original source.
            try {
                pending.add(new PendingFetch(pkg, hex, repoFor(pkg.source(), repoCache)));
            } catch (IllegalArgumentException ignored) {
            } // non-maven source
        }

        HostRateLimiter limiter = HostRateLimiter.shared();
        int fetched = 0;
        List<java.util.concurrent.CompletableFuture<FetchResult>> futures = new ArrayList<>();
        for (PendingFetch p : pending) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> fetchSources(p, limiter), dev.jkbuild.util.JkThreads.io()));
        }
        for (int i = 0; i < futures.size(); i++) {
            FetchResult r;
            try {
                r = futures.get(i).get();
            } catch (java.util.concurrent.ExecutionException e) {
                observer.failed(pending.get(i).pkg, e.getMessage());
                continue;
            }
            if (r.error() == null) {
                fetched++;
                observer.fetched(pending.get(i).pkg);
            } else {
                observer.failed(pending.get(i).pkg, r.error());
            }
        }
        return fetched;
    }

    private static FetchResult fetchSources(PendingFetch p, HostRateLimiter limiter) {
        Coordinate sourcesCoord = new dev.jkbuild.model.Coordinate(
                p.pkg.moduleGroup(), p.pkg.moduleArtifact(), p.pkg.version(), "sources", "jar");
        try {
            URI host = p.repo.baseUrl();
            MavenRepo.Fetched f = limiter.run(host, () -> p.repo.fetchArtifact(sourcesCoord));
            if (!f.sha256().equals(p.expectedHex)) {
                return FetchResult.failure(p.pkg.name() + " sources: checksum mismatch");
            }
            return FetchResult.ok();
        } catch (IOException e) {
            return FetchResult.failure(p.pkg.name() + " sources: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failure(p.pkg.name() + " sources: interrupted");
        }
    }

    /**
     * Per-package callback driven by {@link #sync(Lockfile, ProgressObserver)}. Methods are invoked
     * once per package, on whatever thread resolved that package's outcome. Implementations need to
     * be thread-safe.
     */
    public interface ProgressObserver {
        ProgressObserver NOOP = new ProgressObserver() {};

        default void upToDate(Lockfile.Artifact pkg) {}

        default void fetched(Lockfile.Artifact pkg) {}

        default void skipped(Lockfile.Artifact pkg) {}

        default void failed(Lockfile.Artifact pkg, String error) {}
    }

    private static FetchResult fetch(PendingFetch p, HostRateLimiter limiter) {
        Coordinate coord = toCoord(p.pkg);
        try {
            URI host = p.repo.baseUrl();
            MavenRepo.Fetched f = limiter.run(host, () -> p.repo.fetchArtifact(coord));
            if (!f.sha256().equals(p.expectedHex)) {
                return FetchResult.failure(p.pkg.name()
                        + " v"
                        + p.pkg.version()
                        + ": checksum mismatch — lock says "
                        + p.expectedHex
                        + ", got "
                        + f.sha256());
            }
            return FetchResult.ok();
        } catch (IOException e) {
            return FetchResult.failure(p.pkg.name() + " v" + p.pkg.version() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.failure(p.pkg.name() + " v" + p.pkg.version() + ": interrupted");
        }
    }

    /**
     * True when the artifact's coordinate is already present in the named-repo store
     * ({@code repos/<name>/<m2-path>.sha256} exists). A single stat call, no content read.
     * Returns false for non-Maven sources (git, path, local) or malformed source strings.
     */
    private boolean repoStoreContains(Lockfile.Artifact pkg) {
        String repoName = dev.jkbuild.repo.RepoArtifactResolver.repoName(pkg.source());
        if (repoName == null) return false;
        Coordinate coord = toCoord(pkg);
        String m2Path = dev.jkbuild.repo.MavenLayout.artifactPath(coord);
        // forRepoName() creates an index-only store for non-local repos — contains() checks both
        // the sidecar in repos/<name>/ AND the actual artifact in ~/.m2.
        dev.jkbuild.repo.RepoArtifactStore store = dev.jkbuild.repo.RepoArtifactStore.forRepoName(cas.root(), repoName);
        return store.contains(m2Path);
    }

    /**
     * Ensure a {@code local}-source artifact (installed into the repos/local full store) is present
     * in the CAS under its locked hash, so the compile classpath can resolve it by hash. Returns
     * false when the artifact isn't in the local store (nothing to materialize).
     */
    private boolean materializeLocal(Lockfile.Artifact pkg, String hex) {
        if (cas.contains(hex)) return true;
        dev.jkbuild.repo.RepoArtifactStore local =
                dev.jkbuild.repo.RepoArtifactStore.forRepoName(cas.root(), "local");
        java.util.Optional<java.nio.file.Path> jar =
                local.locate(dev.jkbuild.repo.MavenLayout.artifactPath(toCoord(pkg)));
        if (jar.isEmpty()) return false;
        try {
            cas.putByLink(jar.get(), hex);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private MavenRepo repoFor(String source, Map<String, MavenRepo> cache) {
        MavenRepo existing = cache.get(source);
        if (existing != null) return existing;
        String name = RepoArtifactResolver.repoName(source);
        if (name == null) {
            throw new IllegalArgumentException("lockfile package source must be '<name>+<url>', got: " + source);
        }
        URI url = URI.create(source.substring(source.indexOf('+') + 1));
        var cred = creds.resolve(name, url, java.util.Optional.empty());
        MavenRepo repo = new MavenRepo(name, url, http, cas, cred);
        cache.put(source, repo);
        return repo;
    }

    private static Coordinate toCoord(Lockfile.Artifact pkg) {
        return pkg.coordinate();
    }

    /** A package whose CAS entry is missing and needs to be fetched. */
    private record PendingFetch(Lockfile.Artifact pkg, String expectedHex, MavenRepo repo) {}

    /** Outcome of one parallel fetch — null error means success. */
    private record FetchResult(String error) {
        static FetchResult ok() {
            return new FetchResult(null);
        }

        static FetchResult failure(String msg) {
            return new FetchResult(msg);
        }
    }

    public record Report(int fetched, int upToDate, int skipped, List<String> errors) {
        public Report {
            errors = List.copyOf(errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }
}
