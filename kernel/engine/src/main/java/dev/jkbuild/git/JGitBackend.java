// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.git;

import dev.jkbuild.forge.ForgeGitCredentials;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.util.GitUrl;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Pure-Java {@link GitBackend} backed by JGit. Always available; the fallback when no {@code git}
 * command is found. Runs in-process in the engine JVM.
 */
public final class JGitBackend implements GitBackend {
    private final Path gitRoot;
    private final ForgeGitCredentials credentials;
    static final Pattern DESCRIBE_SUFFIX = Pattern.compile("^(.*)-\\d+-g[0-9a-fA-F]+$");

    public JGitBackend(Path gitRoot, ForgeGitCredentials credentials) {
        this.gitRoot = Objects.requireNonNull(gitRoot, "gitRoot");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /** Build a per-URL credentials provider from the resolved forge token, or null for anonymous. */
    private CredentialsProvider providerFor(String url) {
        String[] c = credentials.resolveCredentials(url);
        if (c == null || c[0] == null || c[0].isBlank()) return null;
        return new UsernamePasswordCredentialsProvider(c[0], c[1] != null ? c[1] : "");
    }

    @Override
    public GitFetcher.RemoteRefs listRefs(GitSource source) throws IOException {
        CredentialsProvider creds = providerFor(source.canonicalUrl());
        try {
            // No setHeads/setTags filter: those exclude the symbolic HEAD. We want every ref and
            // pick out refs/tags/* + HEAD ourselves.
            var cmd = Git.lsRemoteRepository().setRemote(source.canonicalUrl());
            if (creds != null) cmd.setCredentialsProvider(creds);
            List<String> tags = new ArrayList<>();
            String head = null;
            for (Ref r : cmd.call()) {
                String name = r.getName();
                if (name.startsWith("refs/tags/")) {
                    if (name.endsWith("^{}")) continue; // peeled duplicate of an annotated tag
                    tags.add(name.substring("refs/tags/".length()));
                } else if ("HEAD".equals(name) && r.getObjectId() != null) {
                    head = r.getObjectId().getName();
                }
            }
            return new GitFetcher.RemoteRefs(List.copyOf(tags), head);
        } catch (GitAPIException e) {
            throw new IOException("git ls-remote failed: " + e.getMessage(), e);
        }
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
        try (Git git = Git.open(bareDir.toFile());
                RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit c = walk.parseCommit(ObjectId.fromString(sha));
            return new GitFetcher.RefInfo(
                    sha, Instant.ofEpochSecond(c.getCommitTime()), describeNearestTag(git, ObjectId.fromString(sha)));
        } catch (GitAPIException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    Path ensureBareClone(GitSource source) throws IOException {
        CredentialsProvider creds = providerFor(source.canonicalUrl());
        Path bareDir = gitRoot.resolve("db").resolve(GitUrl.canonicalHash(source.canonicalUrl()));
        if (Files.isDirectory(bareDir.resolve("HEAD")) || Files.exists(bareDir.resolve("HEAD"))) {
            try (Git git = Git.open(bareDir.toFile())) {
                var f = git.fetch()
                        .setRemote("origin")
                        .setRemoveDeletedRefs(true)
                        .setTagOpt(TagOpt.FETCH_TAGS)
                        .setRefSpecs(
                                new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"));
                if (creds != null) f.setCredentialsProvider(creds);
                f.call();
            } catch (GitAPIException e) {
                throw new IOException("git fetch failed: " + e.getMessage(), e);
            }
            return bareDir;
        }
        Files.createDirectories(bareDir.getParent());
        try {
            new URIish(source.canonicalUrl());
        } catch (URISyntaxException e) {
            throw new IOException("invalid URL", e);
        }
        try {
            var clone = Git.cloneRepository()
                    .setBare(true)
                    .setURI(source.canonicalUrl())
                    .setDirectory(bareDir.toFile());
            // Explicit tag entries request a shallow clone (depth 1) to save bandwidth.
            // URL-embedded refs and all other ref types use a full clone.
            if (source.shallow()) clone.setDepth(1);
            if (creds != null) clone.setCredentialsProvider(creds);
            clone.call().close();
        } catch (GitAPIException e) {
            deleteRecursively(bareDir);
            throw new IOException("git clone failed: " + e.getMessage(), e);
        }
        return bareDir;
    }

    static String resolveRefSha(GitSource source, Path bareDir) throws IOException {
        GitRefSpec ref = source.ref();
        if (ref instanceof GitRefSpec.Rev rev) {
            try (Git git = Git.open(bareDir.toFile())) {
                ObjectId id = git.getRepository().resolve(rev.sha());
                if (id == null) throw new IOException("rev " + rev.sha() + " not found");
                return id.getName();
            }
        }
        String refName = ref instanceof GitRefSpec.Tag t
                ? "refs/tags/" + t.name()
                : "refs/heads/" + ((GitRefSpec.Branch) ref).name();
        try (Git git = Git.open(bareDir.toFile())) {
            Repository repo = git.getRepository();
            Ref r = repo.findRef(refName);
            if (r == null) throw new IOException("ref " + refName + " not found");
            ObjectId target = r.getPeeledObjectId() != null ? r.getPeeledObjectId() : r.getObjectId();
            return target.getName();
        }
    }

    Path ensureCheckout(GitSource source, Path bareDir, String sha, boolean noCache) throws IOException {
        Path dir = gitRoot.resolve("co")
                .resolve(GitUrl.canonicalHash(source.canonicalUrl()))
                .resolve(sha);
        if (noCache && Files.isDirectory(dir)) deleteRecursively(dir);
        if (Files.isDirectory(dir)) return dir;
        Files.createDirectories(dir.getParent());
        try (Git g = Git.cloneRepository()
                .setURI(bareDir.toUri().toString())
                .setDirectory(dir.toFile())
                .setNoCheckout(true)
                .call()) {
            g.checkout().setName(sha).call();
        } catch (GitAPIException e) {
            deleteRecursively(dir);
            throw new IOException("checkout failed: " + e.getMessage(), e);
        }
        return dir;
    }

    static Optional<String> describeNearestTag(Git git, ObjectId target) throws GitAPIException, IOException {
        String d = git.describe().setTarget(target).setTags(true).call();
        if (d == null || d.isBlank()) return Optional.empty();
        Matcher m = DESCRIBE_SUFFIX.matcher(d);
        return Optional.of(m.matches() ? m.group(1) : d);
    }

    static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
