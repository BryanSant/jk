// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.git;

import cc.jumpkick.forge.ForgeGitCredentials;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.util.GitUrl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link GitBackend} that shells out to a locally installed {@code git} command via {@code
 * ProcessBuilder}. Preferred over {@link JGitExtension} whenever a usable {@code git} is on {@code
 * PATH}. Reproduces JGit's resolved SHAs, commit times and nearest-tag behaviour byte-for-byte, and
 * uses the same on-disk cache layout ({@code db/<hash>} bare clone, {@code co/<hash>/<sha>}
 * checkout).
 *
 * <p>Credentials (forge tokens, HTTP Basic over https) are fed to git through the child process
 * <em>environment</em> ({@code GIT_CONFIG_*} → a URL-scoped {@code http.<url>.extraHeader}) so the
 * secret never appears in {@code argv} ({@code ps}) or on disk.
 */
public final class GitCliExtension implements GitBackend {

    @Override
    public String id() {
        return "git-cli";
    }

    private static final Pattern VERSION = Pattern.compile("^git version (\\d+)\\.(\\d+).*");
    private static final long LOCAL_TIMEOUT_SEC = 120;
    private static final long NETWORK_TIMEOUT_SEC = 600;

    /** A detected, usable {@code git} executable and its version. */
    public record GitCli(String exe, int major, int minor) {
        /** {@code git} ≥ 2.31 supports {@code GIT_CONFIG_COUNT}/{@code GIT_CONFIG_KEY_n} config env. */
        boolean supportsConfigEnv() {
            return major > 2 || (major == 2 && minor >= 31);
        }
    }

    private static volatile Optional<GitCli> cached;

    /** Locate and memoize a usable {@code git} once per process. */
    public static Optional<GitCli> detect() {
        Optional<GitCli> c = cached;
        if (c == null) {
            synchronized (GitCliExtension.class) {
                c = cached;
                if (c == null) {
                    c = probe();
                    cached = c;
                }
            }
        }
        return c;
    }

    private static Optional<GitCli> probe() {
        String override = System.getenv("JK_GIT");
        String exe = (override != null && !override.isBlank()) ? override.trim() : "git";
        try {
            // ProcessBuilder resolves git.exe on Windows via CreateProcess's implicit .exe search;
            // no PATH scanning needed. It does NOT go through cmd.exe, so git.bat/.cmd shims are not
            // found — the canonical installs ship git.exe, which is what we target.
            ProcessBuilder pb = new ProcessBuilder(exe, "--version").redirectErrorStream(true);
            pb.environment().put("LC_ALL", "C");
            Process p = pb.start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return Optional.empty();
            }
            if (p.exitValue() != 0) return Optional.empty();
            Matcher m = VERSION.matcher(out);
            if (!m.find()) return Optional.empty();
            return Optional.of(new GitCli(exe, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        } catch (IOException e) { // command-not-found lands here
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private final Path gitRoot;
    private final ForgeGitCredentials credentials;
    private final GitCli git;

    public GitCliExtension(Path gitRoot, ForgeGitCredentials credentials, GitCli git) {
        this.gitRoot = Objects.requireNonNull(gitRoot, "gitRoot");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.git = Objects.requireNonNull(git, "git");
    }

    // --- operations ----------------------------------------------------------

    @Override
    public GitFetcher.RemoteRefs listRefs(GitSource source) throws IOException {
        String url = source.canonicalUrl();
        ProcResult r = exec(null, url, List.of("ls-remote", url), NETWORK_TIMEOUT_SEC);
        if (r.exit != 0) throw new IOException("git ls-remote failed: " + r.output.strip());
        List<String> tags = new ArrayList<>();
        String head = null;
        for (String line : r.output.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String sha = line.substring(0, tab).strip();
            String name = line.substring(tab + 1).strip();
            if (name.startsWith("refs/tags/")) {
                if (name.endsWith("^{}")) continue; // peeled duplicate of an annotated tag
                tags.add(name.substring("refs/tags/".length()));
            } else if ("HEAD".equals(name) && !sha.isEmpty()) {
                head = sha;
            }
        }
        tags.sort(String::compareTo); // ls-remote order differs from JGit's; normalize
        return new GitFetcher.RemoteRefs(List.copyOf(tags), head);
    }

    @Override
    public GitFetcher.Fetched fetch(GitSource source, boolean noCache) throws IOException {
        Path bareDir = ensureBareClone(source);
        String sha = resolveRefSha(source, bareDir);
        return new GitFetcher.Fetched(sha, ensureCheckout(source, bareDir, sha, noCache));
    }

    @Override
    public void verifyLocked(GitSource source, String expected) throws IOException {
        String actual = resolveRefSha(source, ensureBareClone(source));
        if (!actual.equalsIgnoreCase(expected)) {
            throw new GitFetcher.TagRewriteException(source, expected, actual);
        }
    }

    @Override
    public GitFetcher.RefInfo resolveRef(GitSource source) throws IOException {
        Path bareDir = ensureBareClone(source);
        String sha = resolveRefSha(source, bareDir);
        ProcResult t =
                exec(bareDir, null, List.of("show", "-s", "--format=%ct", "--end-of-options", sha), LOCAL_TIMEOUT_SEC);
        if (t.exit != 0) throw new IOException("git show failed for " + sha + ": " + t.output.strip());
        long epoch;
        try {
            epoch = Long.parseLong(t.output.strip());
        } catch (NumberFormatException e) {
            throw new IOException("unexpected commit-time output: " + t.output.strip(), e);
        }
        return new GitFetcher.RefInfo(sha, Instant.ofEpochSecond(epoch), describeNearestTag(bareDir, sha));
    }

    // --- cache primitives ----------------------------------------------------

    private Path ensureBareClone(GitSource source) throws IOException {
        String url = source.canonicalUrl();
        Path bareDir = gitRoot.resolve("db").resolve(GitUrl.canonicalHash(url));
        if (Files.exists(bareDir.resolve("HEAD"))) {
            ProcResult r = exec(
                    bareDir,
                    url,
                    List.of(
                            "fetch",
                            "--prune",
                            "--prune-tags",
                            "origin",
                            "+refs/heads/*:refs/heads/*",
                            "+refs/tags/*:refs/tags/*"),
                    NETWORK_TIMEOUT_SEC);
            if (r.exit != 0) throw new IOException("git fetch failed: " + r.output.strip());
            return bareDir;
        }
        Files.createDirectories(bareDir.getParent());
        List<String> args = new ArrayList<>(List.of("clone", "--bare"));
        // Explicit tag entries request a shallow clone (depth 1) to save bandwidth; all other ref
        // types use a full clone. `shallow` is set iff the ref is a Tag (GitSource), so pin the
        // clone to that tag with --branch: a bare `--depth 1` only fetches the default-branch tip,
        // which would omit a tag that points elsewhere (JGit's clone auto-follows the tag instead).
        if (source.shallow()) {
            args.add("--depth");
            args.add("1");
            if (source.ref() instanceof GitRefSpec.Tag t) {
                args.add("--branch");
                args.add(t.name());
            }
        }
        args.add("--");
        args.add(url);
        args.add(bareDir.toString());
        ProcResult r = exec(null, url, args, NETWORK_TIMEOUT_SEC);
        if (r.exit != 0) {
            JGitExtension.deleteRecursively(bareDir);
            throw new IOException("git clone failed: " + r.output.strip());
        }
        return bareDir;
    }

    private String resolveRefSha(GitSource source, Path bareDir) throws IOException {
        GitRefSpec ref = source.ref();
        String rev;
        String display;
        switch (ref) {
            case GitRefSpec.Rev r -> {
                rev = r.sha();
                display = r.sha();
            }
            case GitRefSpec.Tag t -> {
                // ^{commit} peels annotated AND lightweight tags to the commit, matching JGit's
                // getPeeledObjectId-else-getObjectId.
                rev = "refs/tags/" + t.name() + "^{commit}";
                display = "refs/tags/" + t.name();
            }
            case GitRefSpec.Branch b -> {
                rev = "refs/heads/" + b.name();
                display = "refs/heads/" + b.name();
            }
        }
        ProcResult res =
                exec(bareDir, null, List.of("rev-parse", "--verify", "--end-of-options", rev), LOCAL_TIMEOUT_SEC);
        if (res.exit != 0) {
            String detail = res.output.strip();
            throw new IOException("ref " + display + " not found" + (detail.isEmpty() ? "" : ": " + detail));
        }
        return res.output.strip();
    }

    private Path ensureCheckout(GitSource source, Path bareDir, String sha, boolean noCache) throws IOException {
        Path dir = gitRoot.resolve("co")
                .resolve(GitUrl.canonicalHash(source.canonicalUrl()))
                .resolve(sha);
        if (noCache && Files.isDirectory(dir)) JGitExtension.deleteRecursively(dir);
        if (Files.isDirectory(dir)) return dir;
        Files.createDirectories(dir.getParent());
        // Local clone from the bare mirror; pass the bare dir as a plain filesystem path (not a
        // file:// URI) so it works on Windows.
        ProcResult clone = exec(
                null,
                null,
                List.of("clone", "--no-checkout", "--", bareDir.toString(), dir.toString()),
                LOCAL_TIMEOUT_SEC);
        if (clone.exit != 0) {
            JGitExtension.deleteRecursively(dir);
            throw new IOException("checkout clone failed: " + clone.output.strip());
        }
        // NB: no `--end-of-options` here. Unlike rev-parse/show/describe, `git checkout --detach`
        // rejects it — git parses the token as a pathspec and fails with "--detach does not take a
        // path argument '--end-of-options'" (seen on git 2.43). `sha` is an already-resolved full
        // 40-hex commit id (resolveRefSha → rev-parse --verify), so it can't be mistaken for an
        // option or a path; the plain form is safe and works across git versions (Linux/macOS).
        ProcResult co = exec(dir, null, List.of("checkout", "--detach", sha), LOCAL_TIMEOUT_SEC);
        if (co.exit != 0) {
            JGitExtension.deleteRecursively(dir);
            throw new IOException("checkout failed: " + co.output.strip());
        }
        return dir;
    }

    private Optional<String> describeNearestTag(Path bareDir, String sha) throws IOException {
        ProcResult d = exec(bareDir, null, List.of("describe", "--tags", "--end-of-options", sha), LOCAL_TIMEOUT_SEC);
        if (d.exit != 0) return Optional.empty(); // "fatal: No names found"
        String out = d.output.strip();
        if (out.isEmpty()) return Optional.empty();
        Matcher m = JGitExtension.DESCRIBE_SUFFIX.matcher(out);
        return Optional.of(m.matches() ? m.group(1) : out);
    }

    // --- process plumbing ----------------------------------------------------

    private record ProcResult(int exit, String output) {}

    /**
     * Run {@code git <args>} with a clean, deterministic environment. When {@code credUrl} is a
     * https URL with resolvable forge credentials, inject an {@code Authorization: Basic} header
     * scoped to that URL through the environment (never argv).
     */
    private ProcResult exec(Path cwd, String credUrl, List<String> args, long timeoutSec) throws IOException {
        List<String> lead = new ArrayList<>();
        Map<String, String> credEnv = new HashMap<>();
        if (credUrl != null) prepareCredentials(credUrl, lead, credEnv);

        List<String> cmd = new ArrayList<>();
        cmd.add(git.exe());
        cmd.addAll(lead);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (cwd != null) pb.directory(cwd.toFile());
        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_PAGER", "cat");
        env.put("LC_ALL", "C");
        env.putAll(credEnv);

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("failed to launch git: " + e.getMessage(), e);
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                p.getInputStream().transferTo(buf);
            } catch (IOException ignored) {
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean done;
        try {
            done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("git interrupted", e);
        }
        if (!done) {
            p.destroyForcibly();
            throw new IOException("git timed out after " + timeoutSec + "s: git " + String.join(" ", args));
        }
        try {
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ProcResult(p.exitValue(), buf.toString(StandardCharsets.UTF_8));
    }

    /** Populate leading {@code -c} args and/or credential env for an authenticated https remote. */
    private void prepareCredentials(String url, List<String> lead, Map<String, String> env) {
        if (!url.startsWith("https://")) return;
        String[] c = credentials.resolveCredentials(url);
        if (c == null || c[0] == null || c[0].isBlank()) return;
        String user = c[0];
        String pass = c[1] != null ? c[1] : "";
        if (git.supportsConfigEnv()) {
            String basic = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
            // URL-scoped so the Authorization header can't leak on a cross-host redirect.
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http." + url + ".extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        } else {
            // git < 2.31: inline credential helper that reads the secret from the environment
            // (Git-for-Windows runs helpers via its bundled sh). Secret stays out of argv.
            env.put("JK_GIT_CRED_USER", user);
            env.put("JK_GIT_CRED_PASS", pass);
            lead.add("-c");
            lead.add("credential.helper=");
            lead.add("-c");
            lead.add("credential.helper=!f() { test \"$1\" = get && "
                    + "echo username=$JK_GIT_CRED_USER && echo password=$JK_GIT_CRED_PASS; }; f");
        }
    }
}
