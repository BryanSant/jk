// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.forge.ForgeGitCredentials;
import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.GitVersion;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.GitUrl;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Materializes a git-source dependency into a locally-published Maven artifact
 * (docs/git-source-deps.md): clone the repo at the ref, build it with {@link SourceProjectBuilder}
 * (a jk, Gradle, or Maven target — compile/package only, never tests), and write the jar + POM into
 * a per-commit {@code file://} Maven repository. The result hands the resolver a concrete coordinate
 * it can pin and a {@code file://} repo to fetch from — so the PubGrub solver never has to know
 * about git. A jk target's version is ref-derived; a foreign target declares its own.
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

        Path projectDir = source.path() != null && !source.path().isBlank()
                ? fetched.checkoutPath().resolve(source.path())
                : fetched.checkoutPath();

        // Per-commit dirs; reused on a cache hit (immutable tag/rev).
        Path shaDir = artifactsRoot.resolve(GitUrl.canonicalHash(source.canonicalUrl())).resolve(sha);
        Path repo = shaDir.resolve("repo");
        Lockfile.Artifact.GitInfo gitInfo = new Lockfile.Artifact.GitInfo(
                source.canonicalUrl(), sha, source.ref().token());

        boolean isJk = Files.isRegularFile(projectDir.resolve("jk.toml"));

        // Determine the coordinate. For a jk target it's read cheaply from [project] (+ the
        // ref-derived version), so an already-built commit is a cache hit with no build. A foreign
        // (Gradle/Maven) target only reveals its GAV once built — cache it in a coordinate marker.
        String group = null;
        String artifact = null;
        String version = null;
        String versionOverride = null;
        Path marker = shaDir.resolve("coordinate.txt");
        if (isJk) {
            JkBuild project = JkBuildParser.parse(Files.readString(projectDir.resolve("jk.toml")));
            group = project.project().group();
            artifact = project.project().name();
            version = deriveVersion(fetcher, source, sha);
            versionOverride = version; // git deps override the jk.toml version with the ref-derived one
        } else if (Files.isRegularFile(marker)) {
            String[] gav = Files.readString(marker).strip().split(":", 3);
            group = gav[0];
            artifact = gav[1];
            version = gav[2];
        }

        // Cache hit: coordinate known and the artifact is already installed.
        if (group != null && Files.isRegularFile(artifactJar(repo, group, artifact, version))
                && Files.isRegularFile(artifactPom(repo, group, artifact, version))) {
            return new Materialized(group, artifact, version, repo.toUri(), gitInfo);
        }

        SourceProjectBuilder.Built built = SourceProjectBuilder.build(
                projectDir, versionOverride, javaHome, cas, buildRepos, jkVersion);
        group = built.group();
        artifact = built.artifact();
        version = built.version();
        installArtifact(repo, group, artifact, version, built.jar(), built.pomXml());
        if (!isJk) {
            Files.writeString(marker, built.coordinate());
        }
        return new Materialized(group, artifact, version, repo.toUri(), gitInfo);
    }

    private static Path artifactJar(Path repo, String group, String artifact, String version) {
        return repo.resolve(group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar");
    }

    private static Path artifactPom(Path repo, String group, String artifact, String version) {
        return repo.resolve(group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom");
    }

    /** Copy the built jar + POM into the {@code file://} repo and (re)write maven-metadata.xml. */
    static void installArtifact(Path repo, String group, String artifact, String version, Path builtJar, String pomXml)
            throws IOException {
        Path jarPath = artifactJar(repo, group, artifact, version);
        Path pomPath = artifactPom(repo, group, artifact, version);
        Files.createDirectories(jarPath.getParent());
        // Streaming copy from the build-output jar — never buffers the whole jar in the heap.
        Files.copy(builtJar, jarPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(pomPath, pomXml);

        // maven-metadata.xml lets the resolver enumerate this artifact's versions through the
        // file:// repo (one version per source dir).
        Path metaPath = repo.resolve(group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml");
        Files.createDirectories(metaPath.getParent());
        Files.writeString(metaPath, metadataXml(group, artifact, version));
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
