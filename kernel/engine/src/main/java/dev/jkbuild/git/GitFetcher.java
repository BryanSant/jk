// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.forge.ForgeGitCredentials;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Git resolver (PRD §11). Thin driver: writes a spec file and forks the {@code jk-git-runner}
 * worker subprocess so JGit never loads in the main jk process. Public API is identical to the
 * former JGit-direct implementation; callers need no changes.
 *
 * <p>Every ref type (tag, branch, rev) is fetched and pinned in {@code jk.lock} like any other
 * coordinate — there is no local freshness-window cache standing in for that pin. An ordinary
 * build never calls {@link #fetch} for a git dep that's already resolved in the lockfile; only
 * {@code jk update --git} / {@code jk fetch} force a fresh resolve.
 *
 * <p>Cache layout (same as before, worker uses the same directories):
 *
 * <pre>
 *   &lt;root&gt;/db/&lt;sha256(canonical-url)&gt;/             # bare clone per URL
 *   &lt;root&gt;/co/&lt;sha256(canonical-url)&gt;/&lt;sha&gt;/   # checkout per resolved SHA
 * </pre>
 */
public final class GitFetcher {

    private final Path gitRoot;
    private final ForgeGitCredentials credentials;

    public GitFetcher(Path gitRoot) {
        this(gitRoot, new ForgeGitCredentials());
    }

    /** Test seam: inject custom credentials resolver. */
    public GitFetcher(Path gitRoot, ForgeGitCredentials credentials) {
        this.gitRoot = Objects.requireNonNull(gitRoot, "gitRoot");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    public record Fetched(String sha, Path checkoutPath) {
        public Fetched {
            Objects.requireNonNull(sha, "sha");
            Objects.requireNonNull(checkoutPath, "checkoutPath");
        }
    }

    public record RefInfo(String sha, Instant commitTime, Optional<String> nearestTag) {
        public RefInfo {
            Objects.requireNonNull(sha, "sha");
            Objects.requireNonNull(commitTime, "commitTime");
            Objects.requireNonNull(nearestTag, "nearestTag");
        }
    }

    /** Resolve and checkout, using the cache. */
    public Fetched fetch(GitSource source) throws IOException {
        return fetch(source, false);
    }

    /** Resolve and checkout, optionally bypassing the checkout cache. */
    public Fetched fetch(GitSource source, boolean noCache) throws IOException {
        Objects.requireNonNull(source, "source");
        Result r = runWorker("fetch", source, noCache, null);
        if (!r.ok) throw new IOException(r.error);
        return new Fetched(r.sha, Path.of(r.checkout));
    }

    /** Tag-rewrite check: re-resolve and fail if the SHA changed. */
    public void verifyLocked(GitSource source, String expectedSha) throws IOException {
        Objects.requireNonNull(source, "source");
        Result r = runWorker("verify_locked", source, false, expectedSha);
        if (!r.ok) {
            if (r.tagRewrite) throw new TagRewriteException(source, expectedSha, r.actual);
            throw new IOException(r.error);
        }
    }

    /** Resolve ref to SHA + commit metadata for git-source versioning. */
    public RefInfo resolveRef(GitSource source) throws IOException {
        Objects.requireNonNull(source, "source");
        Result r = runWorker("resolve_ref", source, false, null);
        if (!r.ok) throw new IOException(r.error);
        return new RefInfo(r.sha, Instant.ofEpochSecond(r.commitTime), Optional.ofNullable(r.nearestTag));
    }

    // --- worker dispatch -----------------------------------------------------

    private Result runWorker(String command, GitSource source, boolean noCache, String expectedSha) throws IOException {
        // Resolve credentials in the parent — has access to ForgeAuth/keychain.
        String[] cred = credentials.resolveCredentials(source.canonicalUrl());

        List<String> lines = new ArrayList<>();
        lines.add("COMMAND " + command);
        lines.add("URL " + source.canonicalUrl());
        lines.add("REF_TYPE " + refTypeName(source.ref()));
        lines.add("REF " + refValue(source.ref()));
        lines.add("NO_CACHE " + noCache);
        lines.add("SHALLOW " + source.shallow());
        lines.add("GIT_ROOT " + gitRoot.toAbsolutePath());
        if (cred != null) {
            lines.add("CRED_USER " + cred[0]);
            lines.add("CRED_PASS " + cred[1]);
        }
        if (expectedSha != null) lines.add("EXPECTED_SHA " + expectedSha);

        Path spec = Files.createTempFile("jk-git-", ".spec");
        try {
            // 0600 so credentials are not world-readable.
            try {
                Files.setPosixFilePermissions(spec, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.write(spec, lines, StandardCharsets.UTF_8);

            Path workerJar = WorkerJar.GIT_CLIENT.locate();
            List<String> cmd = dev.jkbuild.worker.JvmOptions.javaCommand(
                    javaExe().toString(),
                    1,
                    List.of("-jar", workerJar.toString(), spec.toAbsolutePath().toString()));

            Result result = new Result();
            StringBuilder diag = new StringBuilder();
            int exit;
            try {
                exit = WorkerProcess.run(
                        cmd,
                        "##JKGIT:",
                        json -> {
                            if (!"result".equals(Ndjson.str(json, "t"))) return;
                            result.ok = Ndjson.bool(json, "ok", true);
                            result.sha = Ndjson.str(json, "sha");
                            result.checkout = Ndjson.str(json, "checkout");
                            result.error = Ndjson.str(json, "error");
                            result.tagRewrite = Ndjson.bool(json, "tag_rewrite", false);
                            result.actual = Ndjson.str(json, "actual");
                            result.commitTime = Ndjson.longValue(json, "commit_time", 0);
                            result.nearestTag = Ndjson.str(json, "nearest_tag");
                        },
                        line -> diag.append(line).append('\n'));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("git operation interrupted", e);
            }
            if (exit != 0 && result.error == null) {
                result.ok = false;
                result.error = "git-runner exited " + exit
                        + (diag.length() > 0 ? ": " + diag.toString().trim() : "");
            }
            return result;
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    // --- helpers -------------------------------------------------------------

    private static String refTypeName(GitRefSpec ref) {
        return switch (ref) {
            case GitRefSpec.Tag ignored -> "Tag";
            case GitRefSpec.Branch ignored -> "Branch";
            case GitRefSpec.Rev ignored -> "Rev";
        };
    }

    private static String refValue(GitRefSpec ref) {
        return switch (ref) {
            case GitRefSpec.Tag t -> t.name();
            case GitRefSpec.Branch b -> b.name();
            case GitRefSpec.Rev r -> r.sha();
        };
    }

    private static Path javaExe() {
        String home = System.getProperty("java.home");
        Path java = Path.of(
                home, "bin", System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return java;
    }

    private static class Result {
        boolean ok = true, tagRewrite;
        String sha, checkout, error, actual, nearestTag;
        long commitTime;
    }

    /**
     * Raised when a git tag's SHA no longer matches the lockfile — indicates the tag was force-pushed
     * (PRD §11.3).
     */
    public static final class TagRewriteException extends IOException {
        private final GitSource source;
        private final String expectedSha;
        private final String actualSha;

        public TagRewriteException(GitSource source, String expectedSha, String actualSha) {
            super("git tag/branch rewrite detected for "
                    + source.canonicalUrl()
                    + " "
                    + source.ref().token()
                    + ": lock says "
                    + expectedSha
                    + ", upstream now resolves to "
                    + actualSha
                    + " (re-run with `jk update` to accept).");
            this.source = source;
            this.expectedSha = expectedSha;
            this.actualSha = actualSha;
        }

        public GitSource source() {
            return source;
        }

        public String expectedSha() {
            return expectedSha;
        }

        public String actualSha() {
            return actualSha;
        }
    }
}
