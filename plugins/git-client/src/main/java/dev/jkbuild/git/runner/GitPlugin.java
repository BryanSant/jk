// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git.runner;

import dev.jkbuild.git.GitFetcherWorker;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code jk-git-runner} plugin: performs JGit operations (clone, resolve-ref,
 * verify-locked) in an isolated child JVM, keeping JGit off jk's own classpath.
 *
 * <p>This is the first runner converted to the {@link Plugin} SPI
 * (docs/plugin-refactor.md, Phase 1): the worker entry point is the shared
 * {@link dev.jkbuild.plugin.worker.PluginWorkerMain}, which discovers this class via
 * {@link java.util.ServiceLoader} and drives it. The hand-rolled {@code main()},
 * NDJSON escaping, and exit-code plumbing the old {@code GitRunner} carried are
 * now shared infrastructure.
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
public final class GitPlugin implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-git-client", "##JKGIT:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-git-runner: expected spec file path");
            return 2;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-git-runner: spec not found: " + specFile);
            return 2;
        }

        String command = null, url = null, refType = null, ref = null;
        String credUser = null, credPass = null, expectedSha = null;
        Path gitRoot = null;
        boolean noCache = false;

        try {
            for (String line : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = line.indexOf(' ');
                if (sp < 0) continue;
                String key = line.substring(0, sp), val = line.substring(sp + 1).strip();
                switch (key) {
                    case "COMMAND" -> command = val;
                    case "URL" -> url = val;
                    case "REF_TYPE" -> refType = val;
                    case "REF" -> ref = val;
                    case "NO_CACHE" -> noCache = "true".equalsIgnoreCase(val);
                    case "GIT_ROOT" -> gitRoot = Path.of(val);
                    case "CRED_USER" -> credUser = val;
                    case "CRED_PASS" -> credPass = val;
                    case "EXPECTED_SHA" -> expectedSha = val;
                    default -> {}
                }
            }
        } catch (IOException e) {
            System.err.println("jk-git-runner: could not read spec: " + e.getMessage());
            return 2;
        }

        if (command == null || url == null || gitRoot == null) {
            System.err.println("jk-git-runner: spec missing COMMAND, URL, or GIT_ROOT");
            return 2;
        }

        GitFetcherWorker worker = new GitFetcherWorker(gitRoot, credUser, credPass);
        GitSource source = buildSource(url, refType, ref);

        return switch (command) {
            case "fetch" -> doFetch(out, worker, source, noCache);
            case "resolve_ref" -> doResolveRef(out, worker, source);
            case "verify_locked" -> doVerifyLocked(out, worker, source, expectedSha);
            default -> {
                System.err.println("jk-git-runner: unknown command: " + command);
                yield 2;
            }
        };
    }

    private static int doFetch(ProtocolWriter out, GitFetcherWorker w, GitSource source, boolean noCache) {
        try {
            out.emit("{\"t\":\"progress\",\"msg\":" + Ndjson.quote("Fetching " + source.canonicalUrl()) + "}");
            GitFetcherWorker.Fetched r = w.fetch(source, noCache);
            out.emit("{\"t\":\"result\",\"ok\":true,\"sha\":" + Ndjson.quote(r.sha()) + ",\"checkout\":"
                    + Ndjson.quote(r.checkoutPath().toAbsolutePath().toString()) + "}");
            return 0;
        } catch (IOException e) {
            out.emit("{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static int doResolveRef(ProtocolWriter out, GitFetcherWorker w, GitSource source) {
        try {
            GitFetcherWorker.RefInfo r = w.resolveRef(source);
            out.emit("{\"t\":\"result\",\"ok\":true,\"sha\":" + Ndjson.quote(r.sha())
                    + ",\"commit_time\":" + r.commitTime().getEpochSecond()
                    + ",\"nearest_tag\":"
                    + (r.nearestTag().isPresent() ? Ndjson.quote(r.nearestTag().get()) : "null")
                    + "}");
            return 0;
        } catch (IOException e) {
            out.emit("{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static int doVerifyLocked(ProtocolWriter out, GitFetcherWorker w, GitSource source, String expectedSha) {
        if (expectedSha == null) {
            System.err.println("jk-git-runner: verify_locked requires EXPECTED_SHA");
            return 2;
        }
        try {
            w.verifyLocked(source, expectedSha);
            out.emit("{\"t\":\"result\",\"ok\":true}");
            return 0;
        } catch (GitFetcherWorker.TagRewriteException e) {
            out.emit("{\"t\":\"result\",\"ok\":false,\"tag_rewrite\":true"
                    + ",\"expected\":" + Ndjson.quote(e.expectedSha())
                    + ",\"actual\":" + Ndjson.quote(e.actualSha())
                    + ",\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        } catch (IOException e) {
            out.emit("{\"t\":\"result\",\"ok\":false,\"error\":" + Ndjson.quote(e.getMessage()) + "}");
            return 1;
        }
    }

    private static GitSource buildSource(String url, String refType, String ref) {
        GitRefSpec refSpec =
                switch (refType != null ? refType : "Rev") {
                    case "Tag" -> new GitRefSpec.Tag(ref);
                    case "Branch" -> new GitRefSpec.Branch(ref);
                    default -> new GitRefSpec.Rev(ref);
                };
        return new GitSource(url, url, refSpec, null, true, false);
    }
}
