// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.lock.Lockfile;
import build.jumpkick.model.Coordinate;
import build.jumpkick.repo.RepoGroup;
import build.jumpkick.util.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The <a href="https://github.com/oracle/graalvm-reachability-metadata">GraalVM reachability
 * metadata repository</a>, consumed for {@code jk native} builds (spring-boot plan §3.5 — but
 * general: any native build benefits). Third-party libraries that need reflection/resource/JNI
 * config publish it here rather than in their own jars; the native-image invocation gets each
 * matched directory via {@code -H:ConfigurationFileDirectories}.
 *
 * <p>The repository ships as a Maven artifact ({@code repository} classifier zip), extracted once
 * per version into the jk cache. Matching per locked artifact {@code g:a:v}:
 *
 * <ol>
 *   <li>{@code <repo>/<group>/<artifact>/index.json} — absent means no metadata, skip.
 *   <li>The entry whose {@code tested-versions} contains the exact locked version wins.
 *   <li>Otherwise the {@code "latest": true} entry is used best-effort (newer library releases
 *       usually keep the same reflective surface; native-image ignores config for classes that
 *       don't exist).
 * </ol>
 *
 * <p>Failures degrade to an empty result — a native build must never break because a metadata
 * lookup couldn't run; it just builds with whatever hints the classpath itself carries.
 */
final class ReachabilityMetadata {

    /** Repository release consumed by this jk version — walk forward with jk releases. */
    static final String VERSION = "1.1.4";

    private static final String GROUP = "org.graalvm.buildtools";
    private static final String ARTIFACT = "graalvm-reachability-metadata";

    private ReachabilityMetadata() {}

    /**
     * Matched config directories for {@code artifacts} (the RUNTIME lock entries), fetching and
     * extracting the repository on first use. Logs matches through {@code log}; returns an empty
     * list when the repo is unavailable (offline) or nothing matches.
     */
    static List<Path> configDirs(Path cache, RepoGroup repos, List<Lockfile.Artifact> artifacts, Consumer<String> log) {
        Path repoRoot;
        try {
            repoRoot = ensureExtracted(cache, repos);
        } catch (IOException e) {
            log.accept("reachability metadata unavailable (" + e.getMessage() + ") — building without it");
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        List<Path> dirs = new ArrayList<>();
        for (Lockfile.Artifact artifact : artifacts) {
            Path dir = match(repoRoot, artifact.moduleGroup(), artifact.moduleArtifact(), artifact.version(), log);
            if (dir != null) dirs.add(dir);
        }
        return dirs;
    }

    /**
     * The config dir for one coordinate, or null. Exact tested-version match wins; the
     * {@code latest} entry is the best-effort fallback.
     */
    private static Path match(Path repoRoot, String group, String artifact, String version, Consumer<String> log) {
        Path artifactDir = repoRoot.resolve(group).resolve(artifact);
        Path index = artifactDir.resolve("index.json");
        if (!Files.isRegularFile(index)) return null;
        List<?> entries;
        try {
            Object parsed = MiniJson.parse(Files.readString(index, StandardCharsets.UTF_8));
            if (!(parsed instanceof List<?> list)) return null;
            entries = list;
        } catch (IOException | RuntimeException e) {
            log.accept("reachability index unreadable for " + group + ":" + artifact + " — skipped");
            return null;
        }

        String exact = null;
        String latest = null;
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> entry)) continue;
            Object metadataVersion = entry.get("metadata-version");
            if (!(metadataVersion instanceof String mv)) continue;
            if (entry.get("tested-versions") instanceof List<?> tested && tested.contains(version)) {
                exact = mv;
                break;
            }
            if (Boolean.TRUE.equals(entry.get("latest"))) latest = mv;
        }
        String chosen = exact != null ? exact : latest;
        if (chosen == null) return null;
        Path dir = artifactDir.resolve(chosen);
        if (!Files.isDirectory(dir)) return null;
        log.accept("reachability metadata: " + group + ":" + artifact + "@" + version + " → " + chosen
                + (exact == null ? " (latest, untested for this version)" : ""));
        return dir;
    }

    /**
     * Fetch + extract the repository zip once per {@link #VERSION}; concurrent-safe via
     * extract-to-temp + atomic move, with a marker check for the fast path.
     */
    private static Path ensureExtracted(Path cache, RepoGroup repos) throws IOException, InterruptedException {
        Path root = cache.resolve("graal-reachability").resolve(VERSION);
        Path marker = root.resolve(".complete");
        if (Files.isRegularFile(marker)) return root;

        Path zip = repos.tryFetchArtifact(new Coordinate(GROUP, ARTIFACT, VERSION, "repository", "zip"))
                .orElseThrow(() -> new IOException("cannot fetch " + GROUP + ":" + ARTIFACT + ":" + VERSION))
                .fetched()
                .cachePath();

        Path tmp = Files.createTempDirectory(
                Files.createDirectories(root.getParent()), VERSION + ".extract-");
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = tmp.resolve(entry.getName()).normalize();
                if (!target.startsWith(tmp)) continue; // zip-slip guard
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    // transferTo, NOT a try-with-resources copy — closing the entry stream
                    // would close the whole ZipInputStream after the first file.
                    try (var out = Files.newOutputStream(target)) {
                        in.transferTo(out);
                    }
                }
            }
        }
        Files.writeString(tmp.resolve(".complete"), VERSION);
        try {
            Files.move(tmp, root, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Another build won the race (or non-atomic FS): fine if the winner completed.
            build.jumpkick.util.PathUtil.deleteRecursively(tmp);
            if (!Files.isRegularFile(marker)) throw e;
        }
        return root;
    }
}
