// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.cli.engine;

import build.jumpkick.http.Http;
import build.jumpkick.util.Hashing;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Downloads the engine's fat jar into the side-by-side version layout
 * ({@code ~/.jk/versions/<v>/}, CAS-first) when {@link EngineClient}'s spawn path finds none
 * installed — the client is a JDK installer and JVM launcher by trade, and fetching its own
 * engine is the same move as installing a JDK to host it. There is no separate {@code jk
 * engine fetch} command by design: the download is built into the spawn, so every route that needs an
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
    static final String DEFAULT_RELEASES_URL = "https://jumpkick.build/releases";

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
     * Fetch {@code <releasesBase>/<version>/jk-engine-<version>.jar}, verified against the same
     * release's {@code SHA256SUMS}, ingest it into the CAS, and materialize
     * {@code versions/<version>/}. Returns the materialized engine jar. Throws
     * {@link IOException} with an actionable message on any failure — a partial or unverified
     * jar is never left launchable.
     */
    static Path fetch(URI releasesBase, String version) throws IOException {
        return fetch(
                releasesBase,
                version,
                new build.jumpkick.cache.Cas(build.jumpkick.util.JkDirs.cache()),
                build.jumpkick.cache.VersionStore.current(),
                build.jumpkick.task.CachePruneScheduler.resolveJkExe().map(Path::of).orElse(null));
    }

    /** Root-injected variant — the testable seam; production uses the live {@code ~/.jk} roots. */
    static Path fetch(URI releasesBase, String version,
            build.jumpkick.cache.Cas cas, build.jumpkick.cache.VersionStore store, Path clientBin)
            throws IOException {
        String jarName = "jk-engine-" + version + ".jar";
        URI versionDir = URI.create(releasesBase.toString() + "/" + version + "/");
        Http http = new Http();

        byte[] sumsBytes = get(http, versionDir.resolve("SHA256SUMS"), "release checksums");
        // Authenticity gate (engine-versioning-plan §4): when this host trusts any release key,
        // the sums MUST carry a valid signature — signature-then-hash, before any byte is used.
        // A host with no keys at all (dev builds, pre-signing releases) proceeds on checksums
        // alone (dev builds carry no trust anchors yet).
        var verifier = build.jumpkick.repo.ReleaseVerifier.current(
                build.jumpkick.config.GlobalConfig.releaseTrustedKeys());
        if (verifier.available()) {
            byte[] sig = get(http, versionDir.resolve("SHA256SUMS.sig"), "release signature");
            verifier.verify(sumsBytes, new String(sig, java.nio.charset.StandardCharsets.UTF_8));
        }
        String expectedSha = shaFor(sumsBytes, jarName);
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
        cas.put(jar, actualSha); // already hashed for verification — no second pass
        String clientSha = null;
        if (clientBin != null && Files.isRegularFile(clientBin)) {
            clientSha = Hashing.sha256Hex(clientBin);
            cas.putFile(clientBin, clientSha);
        }
        return store.materialize(version, cas, actualSha, clientSha).engineJar();
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
                            + " (check your network, or materialize the version with `jk self update`)",
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
}
