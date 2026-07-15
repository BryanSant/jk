// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.resolver;

import build.jumpkick.model.Coordinate;
import build.jumpkick.repo.GradleModuleMetadata;
import build.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kotlin-Multiplatform root-module redirect resolution (docs/android-plan.md Step 5, blocker 1).
 *
 * <p>For a module whose POM carries Gradle's published-with-metadata marker, the {@code .module}
 * sidecar decides which platform artifact actually carries the runtime classes for this build's
 * {@code org.gradle.jvm.environment}. The dependency walk substitutes that target for the root's
 * POM-declared platform fallback ({@code -jvm}), and the lock records the root itself as a
 * POM-only alias (no artifact, no classpath entry) — exactly Gradle's variant selection, scoped
 * to the one axis a JVM/Android build needs.
 *
 * <p>Fail-soft by design: a missing/unfetchable/unparseable {@code .module} reads as "no
 * redirect", which is the plain-Maven behavior jk always had.
 */
public final class KmpRedirects {

    /** No-op instance for callers with no repo context (git/path-only locks). */
    public static final KmpRedirects NONE = new KmpRedirects(null, "standard-jvm");

    /** A resolved root: the runtime target plus every platform sibling the POM must not follow. */
    public record Selection(GradleModuleMetadata.Redirect target, Set<String> allTargets) {}

    private final RepoGroup repos;
    private final String jvmEnvironment;
    private final Map<String, Optional<Selection>> cache = new ConcurrentHashMap<>();

    /**
     * Every non-selected platform sibling of every redirected root seen this resolve (A5f
     * finding 20). A platform artifact's own POM can name a SIBLING concretely
     * (datastore-core-okio-jvm → datastore-core-jvm) — an edge that is variant-aware in GMM
     * space but bypasses the root's selection in POM space and double-defines every class at
     * dex. The lock excludes these globally; the selected sibling supplies the classes.
     */
    private final Map<String, String> droppedSiblings = new ConcurrentHashMap<>();

    /**
     * Non-selected platform sibling → the selected sibling that replaces it (grows as
     * selections happen). Exclusion applies only when the selected side is actually in the
     * resolution — a graph reaching a platform artifact with no redirected root keeps it.
     */
    public Map<String, String> droppedSiblings() {
        return java.util.Collections.unmodifiableMap(droppedSiblings);
    }

    public KmpRedirects(RepoGroup repos, String jvmEnvironment) {
        this.repos = repos;
        this.jvmEnvironment = jvmEnvironment == null || jvmEnvironment.isBlank() ? "standard-jvm" : jvmEnvironment;
    }

    /** The redirect selection for {@code module}:{@code version}, or empty when none applies. */
    public Optional<Selection> selectionFor(String module, String version) {
        if (repos == null) return Optional.empty();
        return cache.computeIfAbsent(module + "@" + version, k -> lookup(module, version));
    }

    private Optional<Selection> lookup(String module, String version) {
        try {
            Coordinate coord = Coordinate.ofModule(module, version);
            // The POM is already disk-cached by the dependency walk; the marker comment is the
            // cheap gate that keeps non-KMP modules to zero extra fetches.
            var pomHit = repos.tryFetchPom(coord);
            if (pomHit.isEmpty()) return Optional.empty();
            String rawPom = Files.readString(pomHit.get().fetched().cachePath(), StandardCharsets.UTF_8);
            if (!rawPom.contains(GradleModuleMetadata.POM_MARKER)) return Optional.empty();

            Coordinate moduleCoord =
                    new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "module");
            var moduleHit = repos.tryFetchArtifact(moduleCoord);
            if (moduleHit.isEmpty()) return Optional.empty();

            GradleModuleMetadata gmm = GradleModuleMetadata.parse(
                    moduleHit.get().fetched().cachePath());
            return gmm.runtimeRedirect(jvmEnvironment).map(target -> {
                String selected = target.group() + ":" + target.module();
                for (String sibling : gmm.redirectTargetModules()) {
                    if (!sibling.equals(selected)) droppedSiblings.put(sibling, selected);
                }
                return new Selection(target, gmm.redirectTargetModules());
            });
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Optional.empty(); // fail-soft: plain-Maven view
        }
    }
}
