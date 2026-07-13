// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import dev.jkbuild.http.Http;
import dev.jkbuild.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Downloads the engine's fat jar into {@code ~/.jk/lib/} when {@link EngineClient}'s spawn path
 * finds none installed — the client is a JDK installer and JVM launcher by trade, and fetching its
 * own engine is the same move as installing a JDK to host it. There is no separate {@code jk
 * engine fetch} verb by design: the download is built into the spawn, so every route that needs an
 * engine ({@code jk build}, {@code jk engine start}, an upgrade that left a version-skewed jar
 * behind) self-heals the same way.
 *
 * <p>The release layout is one immutable directory per version with the arch-neutral engine jar at
 * its root ({@code releases/<version>/jk-engine-<version>.jar}); the client always fetches its own
 * baked-in version, never a {@code latest} pointer, so the jar and binary can't skew. Integrity
 * rides the release's {@code SHA256SUMS}: the jar is verified against it and moved into place
 * atomically — a torn download never becomes a launchable engine.
 */
final class EngineJarFetcher {

    /** The release site root; one immutable subdirectory per version. */
    static final String DEFAULT_RELEASES_URL = "https://jkbuild.dev/releases";

    private EngineJarFetcher() {}

    /** The release root, overridable via {@code JK_RELEASES_URL} (tests, mirrors, air-gapped hosts). */
    static URI releasesBase() {
        String override = System.getenv("JK_RELEASES_URL");
        return URI.create(override == null || override.isBlank() ? DEFAULT_RELEASES_URL : override);
    }

    /**
     * Whether the spawn path should attempt a fetch at all: only the native client (the JVM dist
     * hosts the engine itself via {@code --engine-server}), only online, and only for a release
     * version — a {@code -SNAPSHOT} build was never published, so dev/CI setups keep today's
     * clear "engine jar missing" failure instead of a doomed download.
     */
    static boolean applicable(String version, boolean nativeImage, boolean offline) {
        return nativeImage && !offline && !version.endsWith("-SNAPSHOT");
    }

    /**
     * Fetch {@code <releasesBase>/<version>/jk-engine-<version>.jar} into {@code libDir}, verified
     * against the same release's {@code SHA256SUMS}. Writes to a {@code .part} sibling and moves
     * atomically; other {@code jk-engine-*.jar} versions in {@code libDir} are removed best-effort
     * on success. Throws {@link IOException} with an actionable message on any failure — a partial
     * or unverified jar is never left at the final name.
     */
    static Path fetch(URI releasesBase, String version, Path libDir) throws IOException {
        return fetch(
                releasesBase,
                version,
                libDir,
                new dev.jkbuild.cache.Cas(dev.jkbuild.util.JkDirs.cache()),
                dev.jkbuild.cache.VersionStore.current(),
                dev.jkbuild.task.CachePruneScheduler.resolveJkExe().map(Path::of).orElse(null));
    }

    /** Root-injected variant — the testable seam; production uses the live {@code ~/.jk} roots. */
    static Path fetch(URI releasesBase, String version, Path libDir,
            dev.jkbuild.cache.Cas cas, dev.jkbuild.cache.VersionStore store, Path clientBin)
            throws IOException {
        String jarName = "jk-engine-" + version + ".jar";
        URI versionDir = URI.create(releasesBase.toString() + "/" + version + "/");
        Http http = new Http();

        String expectedSha = shaFor(get(http, versionDir.resolve("SHA256SUMS"), "release checksums"), jarName);
        byte[] jar = get(http, versionDir.resolve(jarName), "engine jar");
        String actualSha = Hashing.sha256Hex(jar);
        if (!actualSha.equalsIgnoreCase(expectedSha)) {
            throw new IOException("engine jar checksum mismatch for " + versionDir.resolve(jarName)
                    + " — expected sha256 " + expectedSha + ", got " + actualSha
                    + " (a mirror or proxy may have served a stale/corrupt file)");
        }

        // Verified bytes flow CAS-first into the side-by-side version layout
        // (engine-versioning-plan R1/R2). The running client IS this version's client — hand
        // its own binary over so the materialization is complete (wrapper/self-update parity).
        cas.put(jar); // put(byte[]) stores under the content sha it computes
        String clientSha = null;
        if (clientBin != null && Files.isRegularFile(clientBin)) {
            clientSha = Hashing.sha256Hex(clientBin);
            cas.putFile(clientBin, clientSha);
        }
        store.materialize(version, cas, actualSha, clientSha);
        // Transitional: keep the legacy libDir copy so an older client on this machine still
        // finds its jar; the prune phase retires libDir entirely (engine-versioning-plan P6).
        Files.createDirectories(libDir);
        Path dest = libDir.resolve(jarName);
        Path part = libDir.resolve(jarName + ".part");
        try {
            Files.write(part, jar);
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
        deleteStaleVersions(libDir, dest);
        return dest;
    }

    private static byte[] get(Http http, URI uri, String what) throws IOException {
        HttpResponse<byte[]> response;
        try {
            response = http.get(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading the " + what + " from " + uri);
        } catch (IOException e) {
            throw new IOException(
                    "could not download the " + what + " from " + uri + " — " + e.getMessage()
                            + " (check your network, or install the jar into ~/.jk/lib manually)",
                    e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("could not download the " + what + " from " + uri + " — HTTP "
                    + response.statusCode());
        }
        return response.body();
    }

    /** Parse coreutils-style {@code SHA256SUMS} lines ({@code <hex>  <name>}) for {@code jarName}. */
    private static String shaFor(byte[] sumsBody, String jarName) throws IOException {
        String sums = new String(sumsBody, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : sums.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equals(jarName)) return parts[0];
        }
        throw new IOException("release SHA256SUMS carries no entry for " + jarName
                + " — refusing to install an unverifiable engine jar");
    }

    /** A successful install owns the directory's engine slot: older/newer strays just waste disk. */
    private static void deleteStaleVersions(Path libDir, Path keep) {
        try (var jars = Files.newDirectoryStream(libDir, "jk-engine-*.jar")) {
            for (Path jar : jars) {
                if (!jar.equals(keep)) Files.deleteIfExists(jar);
            }
        } catch (IOException ignored) {
            // Best-effort cleanup; the versioned filename means strays are inert, not harmful.
        }
    }
}
