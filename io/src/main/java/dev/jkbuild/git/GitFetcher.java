// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.util.GitUrl;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * JGit-backed git resolver (PRD §11). Layout (PRD §11.5), relative to the
 * git-cache root the caller passes in (typically {@code $JK_CACHE_DIR/git}):
 *
 * <pre>
 *   &lt;root&gt;/db/&lt;sha256(canonical-url)&gt;/             # bare clone per URL
 *   &lt;root&gt;/co/&lt;sha256(canonical-url)&gt;/&lt;sha&gt;/   # checkout per resolved SHA
 * </pre>
 *
 * <p>Sparse checkout for monorepo subpaths is layered on later.
 */
public final class GitFetcher {

    private final Path gitRoot;

    public GitFetcher(Path gitRoot) {
        this.gitRoot = Objects.requireNonNull(gitRoot, "gitRoot");
    }

    public record Fetched(String sha, Path checkoutPath) {
        public Fetched {
            Objects.requireNonNull(sha, "sha");
            Objects.requireNonNull(checkoutPath, "checkoutPath");
        }
    }

    /**
     * Resolve {@code source.ref()} to a 40-char SHA, ensure a checkout
     * for that SHA exists, and return the path. Idempotent: re-runs
     * against an unchanged ref hit the cache.
     */
    public Fetched fetch(GitSource source) throws IOException {
        return fetch(source, false);
    }

    /**
     * Resolve and checkout, optionally bypassing the checkout cache.
     * When {@code noCache} is {@code true} any existing checkout directory
     * is deleted and recreated from the bare clone, ensuring a clean working
     * tree even if the checkout was previously tampered with.
     */
    public Fetched fetch(GitSource source, boolean noCache) throws IOException {
        Objects.requireNonNull(source, "source");
        Path bareDir = ensureBareClone(source);
        String sha = resolveRefSha(source, bareDir);
        Path checkout = ensureCheckout(source, bareDir, sha, noCache);
        return new Fetched(sha, checkout);
    }

    /**
     * Tag-rewrite check (PRD §11.3): re-resolve the ref against the bare
     * clone (refreshing first) and fail loudly if the SHA no longer matches
     * what the lockfile recorded.
     */
    public void verifyLocked(GitSource source, String expectedSha) throws IOException {
        Path bareDir = ensureBareClone(source);
        String actual = resolveRefSha(source, bareDir);
        if (!actual.equalsIgnoreCase(expectedSha)) {
            throw new TagRewriteException(source, expectedSha, actual);
        }
    }

    // --- internals -------------------------------------------------------

    private Path ensureBareClone(GitSource source) throws IOException {
        String hash = GitUrl.canonicalHash(source.canonicalUrl());
        Path bareDir = gitRoot.resolve("db").resolve(hash);
        if (Files.isDirectory(bareDir.resolve("HEAD"))
                || Files.exists(bareDir.resolve("HEAD"))) {
            // Already cloned; fetch latest so ref resolution sees moves.
            try (Git git = Git.open(bareDir.toFile())) {
                // Force-update tags so a rewritten tag's ref refreshes locally.
                // Default fetch doesn't override an existing tag; the explicit
                // `+refs/tags/*` refspec replicates `git fetch --tags --force`.
                git.fetch()
                        .setRemote("origin")
                        .setRemoveDeletedRefs(true)
                        .setTagOpt(TagOpt.FETCH_TAGS)
                        .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*"),
                                new RefSpec("+refs/tags/*:refs/tags/*"))
                        .call();
            } catch (GitAPIException e) {
                throw new IOException("git fetch failed for " + source.canonicalUrl()
                        + ": " + e.getMessage(), e);
            }
            return bareDir;
        }
        Files.createDirectories(bareDir.getParent());
        try {
            // Validate URL via URIish; rejects malformed input early.
            new URIish(source.canonicalUrl());
        } catch (URISyntaxException e) {
            throw new IOException("invalid git URL: " + source.canonicalUrl(), e);
        }
        try {
            Git.cloneRepository()
                    .setBare(true)
                    .setURI(source.canonicalUrl())
                    .setDirectory(bareDir.toFile())
                    .call()
                    .close();
        } catch (GitAPIException e) {
            // Clean up partially-cloned dir.
            deleteRecursively(bareDir);
            throw new IOException("git clone failed for " + source.canonicalUrl()
                    + ": " + e.getMessage(), e);
        }
        return bareDir;
    }

    private static String resolveRefSha(GitSource source, Path bareDir) throws IOException {
        GitRefSpec ref = source.ref();
        if (ref instanceof GitRefSpec.Rev rev) {
            // SHA is the pin; just sanity-check that it's reachable.
            try (Git git = Git.open(bareDir.toFile())) {
                Repository repo = git.getRepository();
                ObjectId resolved = repo.resolve(rev.sha());
                if (resolved == null) {
                    throw new IOException("rev " + rev.sha() + " not found in "
                            + source.canonicalUrl());
                }
                return resolved.getName();
            }
        }
        String refName = ref instanceof GitRefSpec.Tag t
                ? "refs/tags/" + t.name()
                : "refs/heads/" + ((GitRefSpec.Branch) ref).name();
        try (Git git = Git.open(bareDir.toFile())) {
            Repository repo = git.getRepository();
            Ref jgitRef = repo.findRef(refName);
            if (jgitRef == null) {
                throw new IOException("ref " + refName + " not found in "
                        + source.canonicalUrl());
            }
            ObjectId target = jgitRef.getPeeledObjectId() != null
                    ? jgitRef.getPeeledObjectId() : jgitRef.getObjectId();
            return target.getName();
        }
    }

    private Path ensureCheckout(GitSource source, Path bareDir, String sha, boolean noCache)
            throws IOException {
        String hash = GitUrl.canonicalHash(source.canonicalUrl());
        Path checkoutDir = gitRoot.resolve("co").resolve(hash).resolve(sha);
        if (noCache && Files.isDirectory(checkoutDir)) {
            deleteRecursively(checkoutDir);
        }
        if (Files.isDirectory(checkoutDir)) {
            return checkoutDir;
        }
        Files.createDirectories(checkoutDir.getParent());
        try (Git cloned = Git.cloneRepository()
                .setURI(bareDir.toUri().toString())
                .setDirectory(checkoutDir.toFile())
                .setNoCheckout(true)
                .call()) {
            cloned.checkout().setName(sha).call();
        } catch (GitAPIException e) {
            deleteRecursively(checkoutDir);
            throw new IOException("git checkout of " + sha + " failed: " + e.getMessage(), e);
        }
        return checkoutDir;
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    /**
     * Raised when a git tag's SHA no longer matches what the lockfile
     * recorded — the canary that proves the source was rewritten. Per
     * PRD §11.3 this fails loudly; the user opts in via
     * {@code jk update} or {@code --allow-tag-rewrites}.
     */
    public static final class TagRewriteException extends IOException {
        private final GitSource source;
        private final String expectedSha;
        private final String actualSha;

        public TagRewriteException(GitSource source, String expectedSha, String actualSha) {
            super("git tag/branch rewrite detected for " + source.canonicalUrl()
                    + " " + source.ref().token() + ": lock says " + expectedSha
                    + ", upstream now resolves to " + actualSha
                    + " (re-run with `jk update` to accept).");
            this.source = source;
            this.expectedSha = expectedSha;
            this.actualSha = actualSha;
        }
        public GitSource source() { return source; }
        public String expectedSha() { return expectedSha; }
        public String actualSha() { return actualSha; }
    }
}
