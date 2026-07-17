// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Materializes a local-path dependency into a locally-published Maven artifact — the path-source
 * analogue of {@link GitSourceMaterializer}. It resolves the target directory against the consuming
 * project's directory, builds it via {@link SourceProjectBuilder} (jk / Gradle / Maven, compile or
 * package only, never tests), and writes the jar + POM into a {@code file://} Maven repo so the
 * PubGrub solver treats it as an ordinary coordinate.
 *
 * <p>Unlike a git dep there is no commit SHA to key the cache on, so the repo is keyed by a
 * <b>content fingerprint</b> of the target's source tree (build-output directories excluded): the
 * artifact is rebuilt exactly when the sources change and reused otherwise. The repo lives under
 * {@code $JK_CACHE_DIR/path-artifacts/<pathHash>/<fingerprint>/repo}.
 */
final class PathSourceMaterializer {

    /** Directory names never contributing to the fingerprint (build outputs, VCS/tool metadata). */
    private static final Set<String> IGNORED_DIRS =
            Set.of("build", "target", "out", ".git", ".gradle", ".idea", "node_modules");

    /** Outcome: the published coordinate and the {@code file://} repo. */
    record Materialized(String group, String artifact, String version, URI repoUrl) {
        String coordinate() {
            return group + ":" + artifact;
        }
    }

    private final Path lockRootDir;
    private final Path artifactsRoot;
    private final Cas cas;
    private final RepoGroup buildRepos;
    private final Path javaHome;
    private final String jkVersion;

    /** Production wiring: caches under {@code $JK_CACHE_DIR}. */
    PathSourceMaterializer(Path lockRootDir, Cas cas, RepoGroup buildRepos, Path javaHome, String jkVersion) {
        this(lockRootDir, JkDirs.cache().resolve("path-artifacts"), cas, buildRepos, javaHome, jkVersion);
    }

    /** Visible for tests — inject the artifacts root. */
    PathSourceMaterializer(
            Path lockRootDir, Path artifactsRoot, Cas cas, RepoGroup buildRepos, Path javaHome, String jkVersion) {
        this.lockRootDir = lockRootDir;
        this.artifactsRoot = artifactsRoot;
        this.cas = cas;
        this.buildRepos = buildRepos;
        this.javaHome = javaHome;
        this.jkVersion = jkVersion;
    }

    Materialized materialize(PathSource source) throws IOException, InterruptedException {
        Path projectDir = lockRootDir.resolve(source.rawPath()).normalize();
        if (!Files.isDirectory(projectDir)) {
            throw new IOException(
                    "path dependency `" + source.rawPath() + "` does not resolve to a directory (" + projectDir + ")");
        }

        String pathHash = shortHash(projectDir.toAbsolutePath().toString());
        String fingerprint = fingerprint(projectDir);
        Path fpDir = artifactsRoot.resolve(pathHash).resolve(fingerprint);
        Path repo = fpDir.resolve("repo");
        Path marker = fpDir.resolve("coordinate.txt");

        boolean isJk = Files.isRegularFile(projectDir.resolve("jk.toml"));

        // Coordinate: read cheaply from a jk target's [project]; a foreign target reveals it only
        // after building (cached in the marker for a fingerprint hit).
        String group = null;
        String artifact = null;
        String version = null;
        if (isJk) {
            JkBuild project = JkBuildParser.parse(Files.readString(projectDir.resolve("jk.toml")));
            group = project.project().group();
            artifact = project.project().name();
            version = project.project().version();
        } else if (Files.isRegularFile(marker)) {
            String[] gav = Files.readString(marker).strip().split(":", 3);
            group = gav[0];
            artifact = gav[1];
            version = gav[2];
        }

        if (group != null
                && Files.isRegularFile(artifactPath(repo, group, artifact, version, "jar"))
                && Files.isRegularFile(artifactPath(repo, group, artifact, version, "pom"))) {
            return new Materialized(group, artifact, version, repo.toUri());
        }

        // Path deps never override the target's declared version (there's no ref to derive from).
        SourceProjectBuilder.Built built =
                SourceProjectBuilder.build(projectDir, null, javaHome, cas, buildRepos, jkVersion);
        GitSourceMaterializer.installArtifact(
                repo, built.group(), built.artifact(), built.version(), built.jar(), built.pomXml());
        Files.writeString(marker, built.coordinate() + ":" + built.version());
        return new Materialized(built.group(), built.artifact(), built.version(), repo.toUri());
    }

    private static Path artifactPath(Path repo, String group, String artifact, String version, String ext) {
        return repo.resolve(
                group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "." + ext);
    }

    /**
     * A stable content fingerprint of the target's source tree: SHA-256 over each source file's
     * {@code (relative-path, size, content-hash)}, sorted for order-independence, with build-output
     * and tool-metadata directories excluded so a rebuild doesn't invalidate the cache.
     */
    private static String fingerprint(Path projectDir) throws IOException {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path rel = projectDir.relativize(p);
                if (isIgnored(rel)) continue;
                lines.add(rel.toString().replace('\\', '/') + "\0" + Files.size(p) + "\0" + sha256File(p));
            }
        }
        lines.sort(null);
        MessageDigest md = sha256();
        for (String line : lines) {
            md.update(line.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest()).substring(0, 24);
    }

    private static boolean isIgnored(Path relativePath) {
        for (Path segment : relativePath) {
            if (IGNORED_DIRS.contains(segment.toString())) return true;
        }
        return false;
    }

    private static String sha256File(Path file) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static String shortHash(String value) {
        return HexFormat.of()
                .formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
    }

    private static MessageDigest sha256() {
        return Hashing.newSha256();
    }
}
