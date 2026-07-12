// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.model.Coordinate;
import dev.jkbuild.model.Dependency;
import dev.jkbuild.model.VersionSelector;
import dev.jkbuild.repo.RepoGroup;
import dev.jkbuild.resolver.PubGrubResolver;
import dev.jkbuild.resolver.Resolution;
import dev.jkbuild.resolver.Versions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the KSP2 tool closure — {@code symbol-processing-aa-embeddable} plus its transitive
 * runtime deps — the jars the forked {@code KSPJvmMain} CLI needs on its classpath
 * (android-plan §3.5). Mirrors {@link KotlinBtaResolver}: ordinary Maven artifacts through jk's
 * {@link PubGrubResolver}, the resolved closure cached as CAS content hashes under {@code
 * <cache>/tools/ksp/<version>/closure.shas} so warm builds skip resolution and network.
 *
 * <p>Version selection: KSP2 releases stand alone (the {@code <kotlin>-<ksp>} compound naming is
 * the legacy KSP1 line) and embed their own analysis compiler, so one KSP2 release serves a range
 * of Kotlin versions. jk picks the newest stable standalone release from the repository's version
 * list and records it in the closure cache; there is deliberately no per-project knob yet — add
 * one when a real project needs to hold KSP back.
 */
public final class KspResolver {

    /** The single root coordinate; its POM drags in api + common-deps + the embedded compiler. */
    public static final String KSP_AA_MODULE = "com.google.devtools.ksp:symbol-processing-aa-embeddable";

    /** The CLI entry point inside the closure (ships in {@code symbol-processing-aa-embeddable}). */
    public static final String KSP_MAIN = "com.google.devtools.ksp.cmdline.KSPJvmMain";

    private KspResolver() {}

    /**
     * Pick the KSP2 version to use: the newest stable standalone release (plain semver — legacy
     * {@code <kotlin>-<ksp>} compound versions are excluded).
     */
    public static String discoverVersion(RepoGroup repos) throws IOException, InterruptedException {
        int colon = KSP_AA_MODULE.indexOf(':');
        Coordinate coord =
                Coordinate.of(KSP_AA_MODULE.substring(0, colon), KSP_AA_MODULE.substring(colon + 1), "any");
        List<String> available = repos.availableVersions(coord);
        return available.stream()
                .filter(KspResolver::standalone)
                .filter(Versions::isStable)
                .max(Versions::compare)
                .orElseThrow(() -> new IOException("no standalone KSP2 release found for " + KSP_AA_MODULE));
    }

    /** True for the KSP2 standalone version shape ({@code 2.3.10}), false for {@code 2.0.0-1.0.21}. */
    static boolean standalone(String version) {
        // Legacy compound versions carry a second dotted version after a dash (…-1.0.21);
        // standalone versions have at most a prerelease word there. Three dash-separated
        // dotted-number runs = compound.
        int dash = version.indexOf('-');
        if (dash < 0) return true;
        String suffix = version.substring(dash + 1);
        return !suffix.matches("\\d+(\\.\\d+)+");
    }

    /**
     * Resolve and fetch the KSP2 closure for {@code kspVersion}, returning local CAS jar paths for
     * the fork's classpath.
     */
    public static List<Path> resolveClasspath(RepoGroup repos, Cas cas, String kspVersion)
            throws IOException, InterruptedException {
        Path cacheFile = cacheFile(cas, kspVersion);
        boolean refresh = dev.jkbuild.config.SessionContext.current().config().refreshOr(false);
        if (!refresh) {
            List<Path> cached = readCachedClosure(cacheFile, cas);
            if (cached != null) return cached;
        }

        Dependency root = new Dependency(KSP_AA_MODULE, VersionSelector.parse("=" + kspVersion));
        Resolution resolution = new PubGrubResolver(repos).resolve(List.of(root));
        List<Path> jars = new ArrayList<>();
        List<String> shas = new ArrayList<>();
        for (Resolution.ResolvedModule mod : resolution.modules().values()) {
            // POM-only modules (BOMs/parents) carry no jar — omitted from the classpath.
            var hit = repos.tryFetchArtifact(mod.coordinate());
            if (hit.isEmpty()) continue;
            jars.add(hit.get().fetched().cachePath());
            shas.add(hit.get().fetched().sha256());
        }
        if (jars.isEmpty()) {
            throw new IOException(
                    "KSP closure for " + kspVersion + " resolved to no jars — is " + KSP_AA_MODULE + " reachable?");
        }
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, String.join("\n", shas), StandardCharsets.UTF_8);
        return jars;
    }

    private static Path cacheFile(Cas cas, String version) {
        return cas.root().resolve("tools").resolve("ksp").resolve(version).resolve("closure.shas");
    }

    private static List<Path> readCachedClosure(Path cacheFile, Cas cas) {
        if (!Files.isRegularFile(cacheFile)) return null;
        try {
            List<Path> out = new ArrayList<>();
            for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                String sha = line.strip();
                if (sha.isEmpty()) continue;
                if (!cas.contains(sha)) return null; // evicted — re-resolve
                out.add(cas.pathFor(sha));
            }
            return out.isEmpty() ? null : out;
        } catch (IOException e) {
            return null;
        }
    }
}
