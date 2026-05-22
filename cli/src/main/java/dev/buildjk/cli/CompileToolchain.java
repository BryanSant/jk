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
 * {@code CheckCommand} and {@code TestCommand} agree on lookup order.
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
        return pinned.map(InstalledJdk::home)
                .orElse(Path.of(System.getProperty("java.home")));
    }

    /**
     * Resolve a Kotlin installation, auto-downloading via {@link ToolInstaller}
     * if neither {@code KOTLIN_HOME} nor {@code ~/.jk/tools/kotlin/} is
     * populated.
     *
     * @param cacheDir the {@link Cas} root (typically {@code ~/.jk/cache})
     */
    static Path resolveKotlinHome(Path cacheDir) {
        String env = System.getenv("KOTLIN_HOME");
        if (env != null && !env.isBlank() && Files.exists(Path.of(env))) {
            return Path.of(env);
        }
        Path toolsRoot = Path.of(System.getProperty("user.home"), ".jk", "tools");
        ToolRegistry registry = new ToolRegistry(toolsRoot);
        ToolDistribution dist = KotlinResolver.defaultDistribution();
        Optional<InstalledTool> existing = registry.find(dist.tool(), dist.version());
        if (existing.isPresent()) return existing.get().home();

        // Install on first use.
        System.err.println("Installing Kotlin " + dist.version()
                + " from " + dist.downloadUri() + " ...");
        try {
            InstalledTool installed = new ToolInstaller(new Http(), registry).install(dist);
            return installed.home();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("failed to install Kotlin " + dist.version() + ": "
                    + e.getMessage(), e);
        }
    }
}
