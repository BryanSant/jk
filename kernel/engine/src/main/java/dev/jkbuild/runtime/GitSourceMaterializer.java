// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.forge.ForgeGitCredentials;
import dev.jkbuild.git.GitFetcher;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.GitRefSpec;
import dev.jkbuild.model.GitSource;
import dev.jkbuild.model.GitVersion;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.util.GitUrl;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materializes a git-source dependency into a locally-published Maven artifact
 * (docs/git-source-deps.md): clone the repo at the ref, derive a version, build it with {@link
 * GitProjectBuilder}, and write the jar + POM into a per-commit {@code file://} Maven repository.
 * The result hands the resolver a concrete coordinate it can pin and a {@code file://} repo to
 * fetch from — so the PubGrub solver never has to know about git.
 *
 * <p>The local repo lives under {@code $JK_CACHE_DIR/git-artifacts/<urlhash>/<sha>/repo} keyed by
 * commit SHA, so an immutable tag/rev is built once and cached, while a branch rebuilds when its
 * tip moves.
 */
public final class GitSourceMaterializer {

    /** Outcome: the published coordinate, the {@code file://} repo, and lock provenance. */
    record Materialized(String group, String artifact, String version, URI repoUrl, Lockfile.Artifact.GitInfo gitInfo) {
        String coordinate() {
            return group + ":" + artifact;
        }
    }

    private final Path gitRoot;
    private final Path artifactsRoot;
    private final Cas cas;
    private final RepoGroup buildRepos;
    private final Path javaHome;
    private final String jkVersion;
    private final ForgeGitCredentials credentials;

    /** Production wiring: caches under {@code $JK_CACHE_DIR}, forge-auth git credentials. */
    GitSourceMaterializer(Cas cas, RepoGroup buildRepos, Path javaHome, String jkVersion) {
        this(
                JkDirs.cache().resolve("git"),
                JkDirs.cache().resolve("git-artifacts"),
                cas,
                buildRepos,
                javaHome,
                jkVersion,
                new ForgeGitCredentials());
    }

    /** Visible for tests — inject roots and credentials. */
    GitSourceMaterializer(
            Path gitRoot,
            Path artifactsRoot,
            Cas cas,
            RepoGroup buildRepos,
            Path javaHome,
            String jkVersion,
            ForgeGitCredentials credentials) {
        this.gitRoot = gitRoot;
        this.artifactsRoot = artifactsRoot;
        this.cas = cas;
        this.buildRepos = buildRepos;
        this.javaHome = javaHome;
        this.jkVersion = jkVersion;
        this.credentials = credentials;
    }

    /**
     * Fail loudly if {@code source}'s ref no longer resolves to {@code expectedSha} — the tag/branch
     * was force-moved since the lockfile was written (docs/git-source-deps.md §"Supply-chain
     * safety"). Callers use this on {@code jk lock} for immutable (tag/rev) refs; {@code jk update}
     * skips it to accept the new commit.
     */
    void verifyLocked(GitSource source, String expectedSha) throws IOException {
        new GitFetcher(gitRoot, credentials).verifyLocked(source, expectedSha);
    }

    Materialized materialize(GitSource source) throws IOException, InterruptedException {
        GitFetcher fetcher = new GitFetcher(gitRoot, credentials);
        GitFetcher.Fetched fetched = fetcher.fetch(source);
        String sha = fetched.sha();
        String version = deriveVersion(fetcher, source, sha);

        Path projectDir = source.path() != null && !source.path().isBlank()
                ? fetched.checkoutPath().resolve(source.path())
                : fetched.checkoutPath();
        Path buildFile = projectDir.resolve("jk.toml");
        if (!Files.isRegularFile(buildFile)) {
            throw new IOException("git dependency "
                    + source.canonicalUrl()
                    + " has no jk.toml at "
                    + (source.path() == null ? "repo root" : source.path())
                    + " (only jk.toml builds are supported)");
        }
        JkBuild project = JkBuildParser.parse(Files.readString(buildFile));
        // Coordinate is always read from the cloned repo's [project] — no overrides.
        String group = project.project().group();
        String artifact = project.project().name();

        // Per-commit local Maven repo; reused on a cache hit (immutable tag/rev).
        Path repo = artifactsRoot
                .resolve(GitUrl.canonicalHash(source.canonicalUrl()))
                .resolve(sha)
                .resolve("repo");
        String dir = group.replace('.', '/') + "/" + artifact + "/" + version + "/";
        Path jarPath = repo.resolve(dir + artifact + "-" + version + ".jar");
        Path pomPath = repo.resolve(dir + artifact + "-" + version + ".pom");

        if (!Files.isRegularFile(jarPath) || !Files.isRegularFile(pomPath)) {
            GitProjectBuilder.Built built = GitProjectBuilder.build(
                    projectDir, project, group, artifact, version, javaHome, cas, buildRepos, jkVersion);
            Files.createDirectories(jarPath.getParent());
            Files.write(jarPath, built.jar());
            Files.writeString(pomPath, built.pomXml());
        }

        // maven-metadata.xml lets the resolver enumerate this artifact's
        // versions through the file:// repo (one version per commit dir).
        Path metaPath = repo.resolve(group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml");
        if (!Files.isRegularFile(metaPath)) {
            Files.createDirectories(metaPath.getParent());
            Files.writeString(metaPath, metadataXml(group, artifact, version));
        }

        Lockfile.Artifact.GitInfo gitInfo = new Lockfile.Artifact.GitInfo(
                source.canonicalUrl(), sha, source.ref().token());
        return new Materialized(group, artifact, version, repo.toUri(), gitInfo);
    }

    private static String deriveVersion(GitFetcher fetcher, GitSource source, String sha) throws IOException {
        return switch (source.ref()) {
            case GitRefSpec.Tag t -> GitVersion.fromTag(t.name());
            case GitRefSpec.Branch b -> GitVersion.forBranch(b.name());
            case GitRefSpec.Rev ignored -> {
                // Explicit commit: tag-anchored timestamp pseudo-version.
                GitFetcher.RefInfo info = fetcher.resolveRef(source);
                yield GitVersion.pseudo(info.nearestTag(), info.commitTime(), shortSha(sha));
            }
        };
    }

    private static String shortSha(String sha) {
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    private static String metadataXml(String group, String artifact, String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <latest>%s</latest>
                    <release>%s</release>
                    <versions>
                      <version>%s</version>
                    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, version, version, version);
    }
}
