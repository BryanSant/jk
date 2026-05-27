// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compat;

import dev.jkbuild.discovery.DiscoveredTool;
import dev.jkbuild.discovery.SymlinkProvisioner;
import dev.jkbuild.discovery.ToolHealth;
import dev.jkbuild.discovery.ToolProvisioner;
import dev.jkbuild.discovery.ToolSpec;
import dev.jkbuild.http.Http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * The full provisioning pipeline for build tools (Maven, Gradle,
 * Kotlin). Glues {@link ToolProvisioner} discovery, {@link SymlinkProvisioner}
 * linking, the {@link ToolHealth} broken-link check, and the existing
 * {@link ToolInstaller} download path.
 *
 * <p>Pseudocode the caller could write themselves (and previously did):
 * <pre>
 *   1. find in $JK_CACHE_DIR/tools/&lt;slug&gt;/&lt;version&gt;/
 *   2. if found and healthy: return
 *   3. if found and broken: unlink/delete
 *   4. discover via probe chain → if found and canSymlink: link + return
 *   5. fall back to ToolInstaller.install (network download)
 * </pre>
 */
public final class ToolProvisioning {

    private ToolProvisioning() {}

    public record Result(InstalledTool tool, Source source, String detail) {
        public enum Source { CACHED, LINKED, DOWNLOADED }
        public Result {
            if (tool == null) throw new IllegalArgumentException("tool");
            if (source == null) throw new IllegalArgumentException("source");
            if (detail == null) detail = "";
        }
    }

    /**
     * Provision the tool described by {@code distribution}, preferring
     * a local install over a download. Returns the resolved
     * {@link InstalledTool} and a tag describing how it was obtained
     * (for log output).
     */
    public static Result provision(
            ToolDistribution distribution,
            ToolRegistry registry,
            Http http,
            boolean noDiscover) throws IOException, InterruptedException {
        return provision(distribution, registry, http, noDiscover, false, new ToolProvisioner());
    }

    public static Result provision(
            ToolDistribution distribution,
            ToolRegistry registry,
            Http http,
            boolean noDiscover,
            boolean noCache) throws IOException, InterruptedException {
        return provision(distribution, registry, http, noDiscover, noCache, new ToolProvisioner());
    }

    static Result provision(
            ToolDistribution distribution,
            ToolRegistry registry,
            Http http,
            boolean noDiscover,
            boolean noCache,
            ToolProvisioner provisioner) throws IOException, InterruptedException {

        ToolSpec spec = specFor(distribution);

        // 1. Healthy cached install wins (skipped when noCache).
        Optional<InstalledTool> existing = registry.find(distribution.tool(), distribution.version());
        if (!noCache && existing.isPresent() && isHealthyEntry(spec, existing.get().home())) {
            return new Result(existing.get(), Result.Source.CACHED, "");
        }
        // 2. Broken cache entry — purge and continue.
        if (existing.isPresent()) {
            Path entry = existing.get().home();
            if (Files.isSymbolicLink(entry)) {
                SymlinkProvisioner.unlink(entry);
            } else if (Files.exists(entry)) {
                deleteRecursively(entry);
            }
        }

        // 3. Probe the host for an existing install.
        if (!noDiscover && SymlinkProvisioner.canSymlink()) {
            Optional<DiscoveredTool> hit = provisioner.discover(spec);
            if (hit.isPresent()) {
                Path link = registry.installDir(distribution.tool(), distribution.version());
                SymlinkProvisioner.link(link, hit.get().home());
                return new Result(
                        new InstalledTool(distribution.tool(), distribution.version(), link),
                        Result.Source.LINKED,
                        hit.get().source() + " → " + hit.get().home());
            }
        }

        // 4. Download fallback.
        InstalledTool installed = new ToolInstaller(http, registry).install(distribution);
        return new Result(installed, Result.Source.DOWNLOADED, distribution.downloadUri().toString());
    }

    /**
     * Symlinked entries get the full {@link ToolHealth} check (broken-link
     * detection + version-pin verification — catches silent upstream
     * updates). Real directories we wrote ourselves get the cheap
     * "binary still there?" check — we trust the dir name to encode the
     * version, so a full {@code lib/maven-core-X.jar} probe is overkill.
     */
    private static boolean isHealthyEntry(ToolSpec spec, Path home) {
        if (Files.isSymbolicLink(home)) {
            return ToolHealth.isHealthy(spec, home);
        }
        return Files.exists(ToolHealth.requiredBinary(spec, home));
    }

    private static ToolSpec specFor(ToolDistribution distribution) {
        return new ToolSpec(distribution.tool().slug(), distribution.version(), null);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
