// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.config.ActiveConfig;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.PubGrubResolver;
import dev.jkbuild.resolver.Resolution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the Kotlin Build Tools API <em>implementation</em> closure — the jars the {@code
 * :kotlin-compiler} worker needs on its classpath at runtime ({@code kotlin-build-tools-impl} plus
 * its transitive {@code -api}, {@code compiler-embeddable}, {@code kotlinx-coroutines}, …). The
 * worker is compiled against only the tiny stable API; everything else is fetched here,
 * version-matched to the Kotlin the build targets.
 *
 * <p>Unlike {@link CompileToolchain#resolveKotlinHome} — which provisions a pre-built {@code
 * kotlinc} <em>distribution</em> zip — this goes through jk's Maven {@link PubGrubResolver},
 * because the Build Tools API ships as ordinary Maven artifacts (and is <em>not</em> bundled in the
 * compiler distribution).
 *
 * <p>The resolved closure is cached as a list of CAS content hashes under {@code
 * <cache>/tools/kotlin-bta/<version>/closure.shas}, so a warm build skips resolution and network
 * entirely. The cache self-invalidates if any blob has been evicted from the CAS, or when {@code
 * --refresh} is in effect.
 */
public final class KotlinBtaResolver {

    /** The single root coordinate; its POM drags in the whole compiler closure. */
    public static final String BTA_IMPL_MODULE = "org.jetbrains.kotlin:kotlin-build-tools-impl";

    private KotlinBtaResolver() {}

    /**
     * Resolve and fetch the Build Tools API impl closure for {@code kotlinVersion}, returning the
     * local jar paths (in the CAS) for the worker's classpath.
     *
     * @param repos the repositories to resolve against (build via {@link RepoGroupBuilder#buildFor},
     *     so project mirrors / credentials apply)
     * @param cas the content-addressed store the jars land in
     * @param kotlinVersion the exact Kotlin version to match (e.g. {@code 2.4.0})
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        requireSupportedVersion(kotlinVersion);
        Path cacheFile = cacheFile(cas, kotlinVersion);
        boolean refresh = ActiveConfig.get().refreshOr(false);
        if (!refresh) {
            List<Path> cached = readCachedClosure(cacheFile, cas);
            if (cached != null) return cached;
        }

        Dependency root = new Dependency(BTA_IMPL_MODULE, VersionSelector.parse("=" + kotlinVersion));
        Resolution resolution = new PubGrubResolver(repos).resolve(List.of(root));

        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            String module = mod.module(); // "group:artifact"
            int colon = module.indexOf(':');
            Coordinate coord = Coordinate.of(module.substring(0, colon), module.substring(colon + 1), mod.version());
            // POM-only modules (BOMs/parents) carry no jar — tryFetchArtifact
            // returns empty and we simply omit them from the classpath.
            var hit = repos.tryFetchArtifact(coord);
            if (hit.isEmpty()) continue;
            jars.add(hit.get().fetched().cachePath());
            shas.add(hit.get().fetched().sha256());
        }
        if (jars.isEmpty()) {
            throw new IOException("Kotlin Build Tools closure for "
                    + kotlinVersion
                    + " resolved to no jars — is "
                    + BTA_IMPL_MODULE
                    + ":"
                    + kotlinVersion
                    + " available in the configured repositories?");
        }
        writeCachedClosure(cacheFile, shas);
        return jars;
    }

    /**
     * Resolve (and fetch into the CAS) the version-matched {@code kotlin-stdlib} jar. It must go on
     * the <em>compilation</em> classpath: the worker runs the compiler in-process with no Kotlin
     * distribution, so — unlike the old {@code kotlinc} — nothing auto-supplies the stdlib. The
     * caller pairs this with {@code -no-stdlib}. Already in the CAS after {@link #resolveClasspath}
     * (stdlib is part of the closure), so this is a cache hit, not a download.
     */
    public static Path resolveStdlib(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        Coordinate coord = Coordinate.of("org.jetbrains.kotlin", "kotlin-stdlib", kotlinVersion);
        var hit = repos.tryFetchArtifact(coord);
        if (hit.isEmpty()) {
            throw new IOException("kotlin-stdlib:" + kotlinVersion + " not found in the configured repositories");
        }
        return hit.get().fetched().cachePath();
    }

    /**
     * Guard the 2.4.0 floor: the worker drives the Build Tools API through its {@code
     * KotlinToolchains} entry point, which does not exist before 2.4.0.
     */
    static void requireSupportedVersion(String version) {
        int major = 0;
        int minor = 0;
        String[] parts = version.split("[.-]");
        if (parts.length > 0) major = parseLeadingInt(parts[0]);
        if (parts.length > 1) minor = parseLeadingInt(parts[1]);
        if (major < 2 || (major == 2 && minor < 4)) {
            throw new IllegalArgumentException("jk requires Kotlin 2.4.0 or newer (Build Tools API), but the project "
                    + "targets "
                    + version
                    + ". Pin a newer version in jk.toml (project.kotlin).");
        }
    }

    private static int parseLeadingInt(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return i == 0 ? 0 : Integer.parseInt(s.substring(0, i));
    }

    private static Path cacheFile(Cas cas, String kotlinVersion) {
        return cas.root()
                .resolve("tools")
                .resolve("kotlin-bta")
                .resolve(kotlinVersion)
                .resolve("closure.shas");
    }

    /**
     * Reconstruct a previously resolved closure from its recorded hashes, or {@code null} if there's
     * no record or any blob has been evicted from the CAS (forcing a fresh resolve).
     */
    static List<Path> readCachedClosure(Path cacheFile, Cas cas) throws IOException {
        if (!Files.isRegularFile(cacheFile)) return null;
        List<Path> jars = new ArrayList<>();
        for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
            String sha = line.strip();
            if (sha.isEmpty()) continue;
            if (!cas.contains(sha)) return null; // evicted → re-resolve
            jars.add(cas.pathFor(sha));
        }
        return jars.isEmpty() ? null : jars;
    }

    /** Record the closure's content hashes for a future warm build. */
    static void writeCachedClosure(Path cacheFile, List<String> shas) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, shas, StandardCharsets.UTF_8);
    }
}
