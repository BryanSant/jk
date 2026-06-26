// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.config.ActiveConfig;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Git resolver (PRD §11). Thin driver: writes a spec file and forks the
 * {@code jk-git-runner} worker subprocess so JGit never loads in the main
 * jk process. Public API is identical to the former JGit-direct
 * implementation; callers need no changes.
 *
 * <p>Cache layout (same as before, worker uses the same directories):
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

    /**
     * Resolve and checkout, optionally bypassing the checkout cache. For a moving
     * <em>branch</em> ref, the remote tip is re-resolved at most once per freshness
     * window ({@link GitSource#fetch()}; default 12h) — within the window (or under
     * {@code --offline}) the previously-resolved tip's checkout is reused without a
     * network round-trip. Immutable tag/rev refs are unaffected.
     */
    public Fetched fetch(GitSource source, boolean noCache) throws IOException {
        Objects.requireNonNull(source, "source");
        boolean branch = source.ref() instanceof GitRefSpec.Branch;
        if (branch && !noCache) {
            Fetched cached = freshBranchTip(source);
            if (cached != null) return cached;
            if (offline()) {
                throw new IOException("offline: branch tip for " + source.canonicalUrl()
                        + " is not cached (drop --offline, or run `jk update --git`)");
            }
        }
        Result r = runWorker("fetch", source, noCache, null);
        if (!r.ok) throw new IOException(r.error);
        Fetched f = new Fetched(r.sha, Path.of(r.checkout));
        if (branch) recordBranchTip(source, f);
        return f;
    }

    /**
     * Drop the recorded branch-tip freshness stamp for {@code source}, forcing the
     * next {@link #fetch} to re-resolve the remote tip ({@code jk update --git}).
     */
    public void invalidateBranchTip(GitSource source) {
        try {
            Files.deleteIfExists(metaFile(source));
        } catch (IOException ignored) {
            /* best-effort */
        }
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
                Files.setPosixFilePermissions(
                        spec, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
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

    // --- branch-tip freshness ------------------------------------------------

    private static final long DEFAULT_WINDOW_MS = 12L * 60 * 60 * 1000; // 12h

    /** A {@code <gitRoot>/meta/<hash>} record: {@code <epochMs>\n<sha>\n<checkout>}. */
    private record TipMeta(long epochMs, String sha, String checkout) {}

    /**
     * The previously-resolved branch tip when it's still fresh — within the
     * {@link GitSource#fetch()} window, or any time under {@code --offline} —
     * else {@code null} to force a re-resolve.
     */
    private Fetched freshBranchTip(GitSource source) {
        TipMeta m = readTip(source);
        if (m == null) return null;
        Path checkout = Path.of(m.checkout);
        if (!Files.isDirectory(checkout)) return null;
        if (offline()) return new Fetched(m.sha, checkout); // never hit network offline
        long window = windowMillis(source.fetch());
        if (window < 0) return null; // "always"/"0" → re-resolve
        return System.currentTimeMillis() - m.epochMs < window ? new Fetched(m.sha, checkout) : null;
    }

    private void recordBranchTip(GitSource source, Fetched f) {
        try {
            Path meta = metaFile(source);
            Files.createDirectories(meta.getParent());
            Files.writeString(
                    meta,
                    System.currentTimeMillis() + "\n" + f.sha() + "\n"
                            + f.checkoutPath().toAbsolutePath(),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            /* best-effort: a missed stamp just re-resolves sooner */
        }
    }

    private TipMeta readTip(GitSource source) {
        try {
            Path meta = metaFile(source);
            if (!Files.isRegularFile(meta)) return null;
            List<String> lines = Files.readAllLines(meta, StandardCharsets.UTF_8);
            if (lines.size() < 3) return null;
            return new TipMeta(
                    Long.parseLong(lines.get(0).trim()),
                    lines.get(1).trim(),
                    lines.get(2).trim());
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    private Path metaFile(GitSource source) {
        String key = sha256hex(source.canonicalUrl() + " " + refValue(source.ref()));
        return gitRoot.resolve("meta").resolve(key);
    }

    /** Window in millis: {@code null} → 12h default; {@code "always"}/{@code "0"} → -1 (always re-resolve). */
    static long windowMillis(String fetch) {
        if (fetch == null || fetch.isBlank()) return DEFAULT_WINDOW_MS;
        if ("always".equals(fetch) || "0".equals(fetch)) return -1;
        char unit = fetch.charAt(fetch.length() - 1);
        long n = Long.parseLong(fetch.substring(0, fetch.length() - 1));
        return switch (unit) {
            case 's' -> n * 1000L;
            case 'm' -> n * 60_000L;
            case 'h' -> n * 3_600_000L;
            case 'd' -> n * 86_400_000L;
            default -> DEFAULT_WINDOW_MS;
        };
    }

    private static boolean offline() {
        return ActiveConfig.get().offlineOr(false);
    }

    private static String sha256hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
     * Raised when a git tag's SHA no longer matches the lockfile —
     * indicates the tag was force-pushed (PRD §11.3).
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
