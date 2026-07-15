// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import build.jumpkick.cache.Cas;
import build.jumpkick.kotlin.KotlinResolver;
import build.jumpkick.repo.RepoGroup;
import build.jumpkick.engine.plugin.PluginJar;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prepares everything a Kotlin compile needs to fork the {@code jk-kotlin-compiler} worker: the
 * worker JVM classpath (the worker jar + the resolved Build Tools API implementation closure,
 * version-matched to the project's Kotlin) and the version-matched {@code kotlin-stdlib} for the
 * compilation classpath.
 *
 * <p>Shared by every Kotlin compile entry point ({@code jk build}'s {@code compile-kotlin}, {@code
 * jk compile}, {@code jk run} scripts) so they resolve and locate consistently. Jar location itself
 * is delegated to the shared {@link PluginJar} registry; this class adds only the Kotlin-specific
 * closure/stdlib resolution on top.
 */
public final class KotlinPluginSetup {

    /**
     * Override for the {@code jk-kotlin-compiler} jar path (tests, dev). Takes precedence over the
     * CAS lookup.
     */
    public static final String WORKER_JAR_PROPERTY = PluginJar.KOTLIN_COMPILER.jarProperty();

    private KotlinPluginSetup() {}

    /**
     * @param workerClasspath worker JVM {@code -cp}: worker jar + BTA closure
     * @param stdlib the version-matched kotlin-stdlib for the compile classpath
     */
    public record Prepared(List<Path> workerClasspath, Path stdlib) {}

    /**
     * Resolve the closure + stdlib for {@code kotlinVersion} (null ⇒ jk's default) against {@code
     * repos}, and locate the worker jar.
     */
    public static Prepared prepare(RepoGroup repos, Cas cas, String kotlinVersion)
            throws IOException, InterruptedException {
        String version =
                (kotlinVersion == null || kotlinVersion.isBlank()) ? KotlinResolver.DEFAULT_VERSION : kotlinVersion;
        List<Path> closure = KotlinBtaResolver.resolveClasspath(repos, cas, version);
        Path stdlib = KotlinBtaResolver.resolveStdlib(repos, cas, version);

        List<Path> workerClasspath = new ArrayList<>(closure.size() + 1);
        workerClasspath.add(locateWorkerJar(cas));
        workerClasspath.addAll(closure);
        return new Prepared(workerClasspath, stdlib);
    }

    /**
     * Locate the {@code jk-kotlin-compiler} worker jar via the shared registry: the {@value
     * #WORKER_JAR_PROPERTY} override, then the CAS by expected SHA.
     */
    public static Path locateWorkerJar(Cas cas) {
        return PluginJar.KOTLIN_COMPILER.locate(cas);
    }
}
