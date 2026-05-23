// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.InstalledTool;
import dev.jkbuild.compat.ToolDistribution;
import dev.jkbuild.compat.ToolInstaller;
import dev.jkbuild.compat.ToolProvisioning;
import dev.jkbuild.compat.ToolRegistry;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.kotlin.KotlinResolver;
import dev.jkbuild.util.JkDirs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the toolchain paths the subprocess compile strategies need:
 * the JDK that hosts {@code javac} and the Kotlin distribution that
 * hosts {@code kotlinc}. Centralised so {@code BuildCommand},
 * {@code CompileCommand} and {@code TestCommand} agree on lookup order.
 *
 * <p>Java home order:
 * <ol>
 *   <li>{@code JdkResolver.resolve(projectDir)} — the project's pinned JDK.</li>
 *   <li>{@code System.getProperty("java.home")} — the JVM running jk.</li>
 * </ol>
 *
 * <p>Kotlin home order:
 * <ol>
 *   <li>{@code KOTLIN_HOME} env var — honours SDKMAN / manual installs.</li>
 *   <li>{@code $JK_CACHE_DIR/tools/kotlin/&lt;default&gt;/} — auto-installed
 *       via {@link ToolInstaller} on first use.</li>
 * </ol>
 */
final class CompileToolchain {

    private CompileToolchain() {}

    static Path resolveJavaHome(Path projectDir) {
        Optional<InstalledJdk> pinned;
        try {
            pinned = EnvCommand.resolvePinnedJdk(projectDir, null);
        } catch (IOException e) {
            pinned = Optional.empty();
        }
        return pinned.map(InstalledJdk::home).orElseGet(CompileToolchain::runningJavaHome);
    }

    /**
     * The JDK that's hosting the current jk process, or {@code $JAVA_HOME}
     * when running under GraalVM native-image (which doesn't expose
     * {@code java.home}). Used as the last-resort fallback when no project
     * JDK is pinned; throws an explanatory error if neither is available.
     */
    static Path runningJavaHome() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) home = System.getenv("JAVA_HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException(
                    "Cannot resolve a JDK: no project pin (`.jk-version` or `.sdkmanrc`), "
                    + "no `java.home` (running under native-image?), and `JAVA_HOME` is unset. "
                    + "Pin a JDK with a `.jk-version` file, set `JAVA_HOME`, or run jk on a JVM.");
        }
        return Path.of(home);
    }

    /**
     * Resolve a Kotlin installation, auto-downloading via {@link ToolInstaller}
     * if neither {@code KOTLIN_HOME} nor {@code $JK_CACHE_DIR/tools/kotlin/} is
     * populated.
     *
     * @param cacheDir the {@link Cas} root (typically {@link JkDirs#cache()})
     */
    static Path resolveKotlinHome(Path cacheDir) {
        return resolveKotlinHome(cacheDir, null);
    }

    /**
     * Resolve a Kotlin installation pinned to a specific version (e.g. from
     * a script's {@code //KOTLIN 2.1.0} directive). Passes {@code null} to
     * fall back to the bundled default distribution.
     */
    static Path resolveKotlinHome(Path cacheDir, String versionOverride) {
        // ToolProvisioning already runs the EnvVarProbe (which reads
        // KOTLIN_HOME), so we don't need a separate fast-path. Going
        // through the full pipeline guarantees we leave a symlink under
        // $JK_CACHE_DIR/tools/kotlin/<version>/ — subsequent invocations
        // don't depend on the env var still being set.
        Path toolsRoot = JkDirs.cache().resolve("tools");
        ToolRegistry registry = new ToolRegistry(toolsRoot);
        ToolDistribution dist;
        if (versionOverride == null || versionOverride.isBlank()) {
            dist = KotlinResolver.defaultDistribution();
        } else {
            URI uri = URI.create(
                    "https://github.com/JetBrains/kotlin/releases/download/v" + versionOverride
                            + "/kotlin-compiler-" + versionOverride + ".zip");
            dist = new ToolDistribution(BuildTool.KOTLIN, versionOverride, uri, "zip");
        }
        try {
            ToolProvisioning.Result result = ToolProvisioning.provision(
                    dist, registry, new Http(), /*noDiscover=*/ false);
            switch (result.source()) {
                case LINKED -> System.err.println("Linked Kotlin " + dist.version()
                        + " from " + result.detail());
                case DOWNLOADED -> System.err.println("Installed Kotlin " + dist.version()
                        + " from " + result.detail());
                case CACHED -> { /* silent */ }
            }
            return result.tool().home();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("failed to provision Kotlin " + dist.version() + ": "
                    + e.getMessage(), e);
        }
    }
}
