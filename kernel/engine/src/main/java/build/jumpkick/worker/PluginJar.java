// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.worker;

import build.jumpkick.cache.Cas;
import build.jumpkick.repo.RepoArtifactStore;
import build.jumpkick.util.JkDirs;
import build.jumpkick.model.JkVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The single registry of jk's child-JVM worker jars, and the one place that locates one on disk.
 *
 * <p>Each worker is jk's own tooling — pinned to jk's version, not a project dependency — and is
 * addressed by its Maven coordinate ({@code build.jumpkick:<artifactId>:<version>}). Location order
 * is uniform:
 *
 * <ol>
 *   <li>the {@code -D<jarProperty>} override (tests / dev), then
 *   <li>{@code repos/local/} in the jk cache (populated by {@code ./gradlew <module>:installLocal}
 *       in jk's own tree), then
 *   <li>{@code repos/central/} in the jk cache (populated by {@code jk sync} once the worker is
 *       published).
 * </ol>
 *
 * <p>This enum replaces the seven near-identical {@code *WorkerSetup} locator classes and {@code
 * JkPluginSync}'s hand-maintained worker list — one source of truth for the property name, the
 * coordinate, and the side-load hint.
 */
public enum PluginJar {
    TEST_RUNNER("jk-test-runner", "jk.test.runner.jar", ":test-runner:installLocal"),
    KOTLIN_COMPILER("jk-kotlin-compiler", "jk.kotlin.worker.jar", ":kotlin-compiler:installLocal"),
    JAVA_COMPILER("jk-java-compiler", "jk.java.worker.jar", ":java-compiler:installLocal"),
    AUDITOR("jk-auditor", "jk.auditor.worker.jar", ":auditor:installLocal"),
    PUBLISHER("jk-publisher", "jk.publisher.worker.jar", ":publisher:installLocal"),
    IMAGE_BUILDER("jk-image-builder", "jk.image-builder.worker.jar", ":image-builder:installLocal"),
    COMPAT_BRIDGE("jk-compat-bridge", "jk.compat-bridge.worker.jar", ":compat-bridge:installLocal"),
    FORMATTER("jk-formatter", "jk.formatter.worker.jar", ":formatter:installLocal"),
    SPRING_BOOT("jk-spring-boot", "jk.spring-boot.worker.jar", ":spring-boot:installLocal"),
    ANDROID("jk-android", "jk.android.worker.jar", ":android:installLocal"),
    PROTOBUF("jk-protobuf", "jk.protobuf.worker.jar", ":protobuf:installLocal"),
    SHRINK("jk-shrink", "jk.shrink.worker.jar", ":shrink:installLocal");

    private final String artifactId;
    private final String jarProperty;
    private final String installTask;

    PluginJar(String artifactId, String jarProperty, String installTask) {
        this.artifactId = artifactId;
        this.jarProperty = jarProperty;
        this.installTask = installTask;
    }

    /** Maven artifactId the worker publishes under (group is always {@code build.jumpkick}). */
    public String artifactId() {
        return artifactId;
    }

    /** System property that overrides jar location (tests / dev). */
    public String jarProperty() {
        return jarProperty;
    }

    /** The Gradle task that installs this worker into the local repo. */
    public String installTask() {
        return installTask;
    }

    /**
     * The m2-layout relative path for this worker at its current version.
     * E.g. {@code build/jumpkick/jk-formatter/0.10.0-SNAPSHOT/jk-formatter-0.10.0-SNAPSHOT.jar}.
     */
    private String relativePath() {
        String version = JkVersion.VERSION;
        return "build/jumpkick/" + artifactId + "/" + version + "/" + artifactId + "-" + version + ".jar";
    }

    /**
     * Locate the worker jar: {@code -D<jarProperty>} override first, then {@code repos/local/},
     * then {@code repos/central/}. Throws {@link PluginJarNotFoundException} with side-load
     * instructions if none resolves.
     */
    public Path locate(Cas cas) {
        String override = System.getProperty(jarProperty);
        if (override != null && !override.isBlank()) {
            Path jar = Path.of(override);
            if (Files.isRegularFile(jar)) return jar;
            throw new IllegalStateException(
                    "-D" + jarProperty + " is set to '" + override + "' but no file exists there.");
        }

        Path cacheRoot = cas.root(); // cas root is the jk cache directory (e.g. ~/.jk/cache)
        String relPath = relativePath();
        String coordinate = "build.jumpkick:" + artifactId + ":" + JkVersion.VERSION;
        List<Path> checked = new ArrayList<>();

        for (String repoName : List.of("local", "central")) {
            RepoArtifactStore store = new RepoArtifactStore(cacheRoot, repoName);
            var result = store.locate(relPath);
            if (result.isPresent()) return result.get();
            // Record the path that was checked (the artifact path, not the sidecar)
            checked.add(cacheRoot.resolve("repos").resolve(repoName).resolve(relPath));
        }

        throw new PluginJarNotFoundException(artifactId, coordinate, checked, jarProperty);
    }

    /** Locate using the default jk CAS ({@code $JK_CACHE_DIR}). */
    public Path locate() {
        return locate(new Cas(JkDirs.cache()));
    }

    /** As {@link #locate(Cas)} but {@code null} (not throwing) when the worker can't be located. */
    public Path locateOrNull(Cas cas) {
        try {
            return locate(cas);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The worker whose {@code artifactId} (e.g. {@code jk-git-client}) matches, if any. */
    public static java.util.Optional<PluginJar> byArtifactId(String artifactId) {
        for (PluginJar w : values()) {
            if (w.artifactId.equals(artifactId)) return java.util.Optional.of(w);
        }
        return java.util.Optional.empty();
    }
}
