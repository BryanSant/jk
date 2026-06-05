// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git.runner;

import dev.jkbuild.git.GitFetcherWorker;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the {@code jk-git-runner} worker subprocess.
 * Reads a spec file, dispatches to {@link GitFetcherWorker}, streams NDJSON results.
 *
 * Spec file format:
 *   COMMAND      fetch|resolve_ref|verify_locked
 *   URL          https://github.com/user/repo.git
 *   REF_TYPE     Tag|Branch|Rev
 *   REF          main|v1.0.0|sha
 *   NO_CACHE     false
 *   GIT_ROOT     /abs/path/git-cache
 *   CRED_USER    token       (optional)
 *   CRED_PASS    secret      (optional, spec is 0600)
 *   EXPECTED_SHA abc123      (verify_locked only)
 */
public final class GitRunner {

    public static final String PREFIX = "##JKGIT:";

    private GitRunner() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 1) { err.println("jk-git-runner: expected spec file path"); return 2; }
        Path specFile = Path.of(args[0]);
        if (!Files.isRegularFile(specFile)) { err.println("jk-git-runner: spec not found: " + specFile); return 2; }

        String command = null, url = null, refType = null, ref = null;
        String credUser = null, credPass = null, expectedSha = null;
        Path gitRoot = null;
        boolean noCache = false;

        try {
            for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = line.indexOf(' '); if (sp < 0) continue;
                String key = line.substring(0, sp), val = line.substring(sp + 1).strip();
                switch (key) {
                    case "COMMAND"      -> command     = val;
                    case "URL"          -> url         = val;
                    case "REF_TYPE"     -> refType     = val;
                    case "REF"          -> ref         = val;
                    case "NO_CACHE"     -> noCache     = "true".equalsIgnoreCase(val);
                    case "GIT_ROOT"     -> gitRoot     = Path.of(val);
                    case "CRED_USER"    -> credUser    = val;
                    case "CRED_PASS"    -> credPass    = val;
                    case "EXPECTED_SHA" -> expectedSha = val;
                }
            }
        } catch (IOException e) { err.println("jk-git-runner: could not read spec: " + e.getMessage()); return 2; }

        if (command == null || url == null || gitRoot == null) {
            err.println("jk-git-runner: spec missing COMMAND, URL, or GIT_ROOT"); return 2;
        }

        GitFetcherWorker worker = new GitFetcherWorker(gitRoot, credUser, credPass);
        GitSource source = buildSource(url, refType, ref);

        return switch (command) {
            case "fetch"         -> doFetch(out, worker, source, noCache);
            case "resolve_ref"   -> doResolveRef(out, worker, source);
            case "verify_locked" -> doVerifyLocked(out, worker, source, expectedSha);
            default -> { err.println("jk-git-runner: unknown command: " + command); yield 2; }
        };
    }

    private static int doFetch(PrintStream out, GitFetcherWorker w, GitSource source, boolean noCache) {
        try {
            emit(out, "{\"t\":\"progress\",\"msg\":" + quote("Fetching " + source.canonicalUrl()) + "}");
            GitFetcherWorker.Fetched r = w.fetch(source, noCache);
            emit(out, "{\"t\":\"result\",\"ok\":true,\"sha\":" + quote(r.sha())
                    + ",\"checkout\":" + quote(r.checkoutPath().toAbsolutePath().toString()) + "}");
            return 0;
        } catch (IOException e) {
            emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":" + quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static int doResolveRef(PrintStream out, GitFetcherWorker w, GitSource source) {
        try {
            GitFetcherWorker.RefInfo r = w.resolveRef(source);
            emit(out, "{\"t\":\"result\",\"ok\":true,\"sha\":" + quote(r.sha())
                    + ",\"commit_time\":" + r.commitTime().getEpochSecond()
                    + ",\"nearest_tag\":" + (r.nearestTag().isPresent() ? quote(r.nearestTag().get()) : "null")
                    + "}");
            return 0;
        } catch (IOException e) {
            emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":" + quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static int doVerifyLocked(PrintStream out, GitFetcherWorker w, GitSource source, String expectedSha) {
        if (expectedSha == null) { System.err.println("jk-git-runner: verify_locked requires EXPECTED_SHA"); return 2; }
        try {
            w.verifyLocked(source, expectedSha);
            emit(out, "{\"t\":\"result\",\"ok\":true}");
            return 0;
        } catch (GitFetcherWorker.TagRewriteException e) {
            emit(out, "{\"t\":\"result\",\"ok\":false,\"tag_rewrite\":true"
                    + ",\"expected\":" + quote(e.expectedSha())
                    + ",\"actual\":" + quote(e.actualSha())
                    + ",\"error\":" + quote(e.getMessage()) + "}");
            return 1;
        } catch (IOException e) {
            emit(out, "{\"t\":\"result\",\"ok\":false,\"error\":" + quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static GitSource buildSource(String url, String refType, String ref) {
        GitRefSpec refSpec = switch (refType != null ? refType : "Rev") {
            case "Tag"    -> new GitRefSpec.Tag(ref);
            case "Branch" -> new GitRefSpec.Branch(ref);
            default       -> new GitRefSpec.Rev(ref);
        };
        return new GitSource(url, url, refSpec, null, true, false);
    }

    private static void emit(PrintStream out, String json) { out.println(PREFIX + json); out.flush(); }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\""); else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n"); else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
