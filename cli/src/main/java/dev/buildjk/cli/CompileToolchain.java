// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.cli;

import dev.buildjk.cache.Cas;
import dev.buildjk.compat.InstalledTool;
import dev.buildjk.compat.ToolDistribution;
import dev.buildjk.compat.ToolInstaller;
import dev.buildjk.compat.ToolRegistry;
import dev.buildjk.http.Http;
import dev.buildjk.jdk.InstalledJdk;
import dev.buildjk.kotlin.KotlinResolver;

import java.io.IOException;
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
 *   <li>{@code ~/.jk/tools/kotlin/&lt;default&gt;/} — auto-installed via
 *       {@link ToolInstaller} on first use.</li>
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
     * if neither {@code KOTLIN_HOME} nor {@code ~/.jk/tools/kotlin/} is
     * populated.
     *
     * @param cacheDir the {@link Cas} root (typically {@code ~/.jk/cache})
     */
    static Path resolveKotlinHome(Path cacheDir) {
        // ToolProvisioning already runs the EnvVarProbe (which reads
        // KOTLIN_HOME), so we don't need a separate fast-path. Going
        // through the full pipeline guarantees we leave a symlink under
        // ~/.jk/tools/kotlin/<version>/ — subsequent invocations don't
        // depend on the env var still being set.
        Path toolsRoot = Path.of(System.getProperty("user.home"), ".jk", "tools");
        ToolRegistry registry = new ToolRegistry(toolsRoot);
        ToolDistribution dist = KotlinResolver.defaultDistribution();
        try {
            dev.buildjk.compat.ToolProvisioning.Result result =
                    dev.buildjk.compat.ToolProvisioning.provision(
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
