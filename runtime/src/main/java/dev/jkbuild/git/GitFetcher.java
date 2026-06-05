// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.forge.ForgeAuth;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.runtime.GitWorkerSetup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

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
        return new RefInfo(
                r.sha,
                Instant.ofEpochSecond(r.commitTime),
                Optional.ofNullable(r.nearestTag));
    }

    // --- worker dispatch -----------------------------------------------------

    private Result runWorker(String command, GitSource source,
                              boolean noCache, String expectedSha)
            throws IOException {
        // Resolve credentials in the parent — has access to ForgeAuth/keychain.
        String[] cred = credentials.resolveCredentials(source.canonicalUrl());

        List<String> lines = new ArrayList<>();
        lines.add("COMMAND "  + command);
        lines.add("URL "      + source.canonicalUrl());
        lines.add("REF_TYPE " + refTypeName(source.ref()));
        lines.add("REF "      + refValue(source.ref()));
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
                Files.setPosixFilePermissions(spec,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {}
            Files.write(spec, lines, StandardCharsets.UTF_8);

            Path workerJar = GitWorkerSetup.locateWorkerJar();
            Path javaExe = javaExe();
            Process process = new ProcessBuilder(
                    javaExe.toString(), "-jar",
                    workerJar.toString(), spec.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();

            Result result = new Result();
            StringBuilder diag = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = reader.readLine()) != null) {
                    if (!ln.startsWith("##JKGIT:")) { diag.append(ln).append('\n'); continue; }
                    String json = ln.substring("##JKGIT:".length());
                    String t = readField(json, "t");
                    if ("result".equals(t)) {
                        result.ok = !"false".equals(readBoolField(json, "ok"));
                        result.sha         = readField(json, "sha");
                        result.checkout    = readField(json, "checkout");
                        result.error       = readField(json, "error");
                        result.tagRewrite  = "true".equals(readBoolField(json, "tag_rewrite"));
                        result.actual      = readField(json, "actual");
                        String ct = readNumericField(json, "commit_time");
                        if (ct != null) result.commitTime = Long.parseLong(ct);
                        result.nearestTag  = readField(json, "nearest_tag");
                    }
                }
            }
            int exit = process.waitFor();
            if (exit != 0 && result.error == null) {
                result.ok = false;
                result.error = "git-runner exited " + exit
                        + (diag.length() > 0 ? ": " + diag.toString().trim() : "");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git operation interrupted", e);
        } finally {
            Files.deleteIfExists(spec);
        }
    }

    // --- helpers -------------------------------------------------------------

    private static String refTypeName(GitRefSpec ref) {
        return switch (ref) {
            case GitRefSpec.Tag ignored    -> "Tag";
            case GitRefSpec.Branch ignored -> "Branch";
            case GitRefSpec.Rev ignored    -> "Rev";
        };
    }

    private static String refValue(GitRefSpec ref) {
        return switch (ref) {
            case GitRefSpec.Tag t    -> t.name();
            case GitRefSpec.Branch b -> b.name();
            case GitRefSpec.Rev r    -> r.sha();
        };
    }

    private static Path javaExe() {
        String home = System.getProperty("java.home");
        Path java = Path.of(home, "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        return java;
    }

    private static String readField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int s = json.indexOf(needle); if (s < 0) return null; s += needle.length();
        StringBuilder sb = new StringBuilder();
        for (int i = s; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                if (n == '"') sb.append('"'); else if (n == '\\') sb.append('\\');
                else { sb.append('\\'); sb.append(n); }
            } else if (c == '"') break; else sb.append(c);
        }
        return sb.toString();
    }

    private static String readBoolField(String json, String key) {
        String needle = "\"" + key + "\":";
        int s = json.indexOf(needle); if (s < 0) return null; s += needle.length();
        if (json.startsWith("true", s)) return "true";
        if (json.startsWith("false", s)) return "false";
        return null;
    }

    private static String readNumericField(String json, String key) {
        String needle = "\"" + key + "\":";
        int s = json.indexOf(needle); if (s < 0) return null; s += needle.length();
        int e = s; while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) e++;
        return e > s ? json.substring(s, e) : null;
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
        public GitSource source()    { return source; }
        public String expectedSha()  { return expectedSha; }
        public String actualSha()    { return actualSha; }
    }
}
