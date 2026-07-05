// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.compat.BuildTool;
import dev.jkbuild.compat.ToolDistribution;
import dev.jkbuild.compat.ToolInstaller;
import dev.jkbuild.compat.ToolProvisioning;
import dev.jkbuild.compat.ToolRegistry;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.JdkResolution;
import dev.jkbuild.kotlin.KotlinResolver;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Resolves the toolchain paths the subprocess compile strategies need: the JDK that hosts {@code
 * javac} and the Kotlin distribution that hosts {@code kotlinc}. Centralised so {@code
 * BuildCommand}, {@code CompileCommand} and {@code TestCommand} agree on lookup order.
 *
 * <p>Java home order:
 *
 * <ol>
 *   <li>{@code JdkResolver.resolve(projectDir)} — the project's pinned JDK.
 *   <li>{@code System.getProperty("java.home")} — the JVM running jk.
 * </ol>
 *
 * <p>Kotlin home order:
 *
 * <ol>
 *   <li>{@code KOTLIN_HOME} env var — honours SDKMAN / manual installs.
 *   <li>{@code $JK_CACHE_DIR/tools/kotlin/&lt;default&gt;/} — auto-installed via {@link
 *       ToolInstaller} on first use.
 * </ol>
 */
public final class CompileToolchain {

    private CompileToolchain() {}

    public static Path resolveJavaHome(Path projectDir) {
        try {
            dev.jkbuild.lock.Lockfile lock = readLockSoft(projectDir);
            JkBuild build = readBuildSoft(projectDir);
            JdkResolution.Request req = new JdkResolution.Request(
                    projectDir,
                    dev.jkbuild.config.SessionContext.current().jdkSpec(),
                    System.getenv("JK_JDK"),
                    lock != null ? lock.jdk() : null,
                    (build != null && build.project() != null) ? build.project().jdk() : null,
                    (build != null && build.project() != null) ? build.project().javaRelease() : 0,
                    System::getenv);
            // Non-installing walk of the canonical order — JdkEnsure already
            // installed any pin during sync, so this just locates it. Falls back
            // to the running JVM when nothing resolves.
            JdkResolution.Resolved r = JdkResolution.resolveForHook(
                    req, new dev.jkbuild.jdk.JdkRegistry(), dev.jkbuild.jdk.GlobalDefaultJdk.current());
            if (r.jdk().isPresent()) return r.jdk().get().home();
        } catch (RuntimeException ignored) {
            // fall through to the running JVM
        }
        return runningJavaHome();
    }

    private static dev.jkbuild.lock.Lockfile readLockSoft(Path projectDir) {
        try {
            Path lock = projectDir.resolve("jk.lock");
            return Files.isRegularFile(lock) ? dev.jkbuild.lock.LockfileReader.read(lock) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static JkBuild readBuildSoft(Path projectDir) {
        try {
            Path toml = projectDir.resolve("jk.toml");
            return Files.isRegularFile(toml) ? dev.jkbuild.config.JkBuildParser.parse(toml) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The JDK that's hosting the current jk process, or {@code $JAVA_HOME} when running under GraalVM
     * native-image (which doesn't expose {@code java.home}). Used as the last-resort fallback when no
     * project JDK is pinned; throws an explanatory error if neither is available.
     */
    public static Path runningJavaHome() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) home = System.getenv("JAVA_HOME");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("Cannot resolve a JDK: no project pin (`.jdk-version` or `.sdkmanrc`), "
                    + "no `java.home` (running under native-image?), and `JAVA_HOME` is unset. "
                    + "Pin a JDK with a `.jdk-version` file, set `JAVA_HOME`, or run jk on a JVM.");
        }
        return Path.of(home);
    }

    /**
     * Resolve a Kotlin installation, auto-downloading via {@link ToolInstaller} if neither {@code
     * KOTLIN_HOME} nor {@code $JK_CACHE_DIR/tools/kotlin/} is populated.
     *
     * @param cacheDir the {@link Cas} root (typically {@link JkDirs#cache()})
     */
    public static Path resolveKotlinHome(Path cacheDir) {
        return resolveKotlinHome(cacheDir, null, NO_NOTICE);
    }

    /** Notice sink that drops provisioning messages (no-op). */
    private static final Consumer<String> NO_NOTICE = s -> {};

    /**
     * Pick the Kotlin compiler version to provision: the version pinned in {@code jk.lock} (resolved
     * by {@code jk lock}) if present, else an exact {@code project.kotlin} pin, else {@code null} —
     * which falls back to the bundled default distribution.
     */
    public static String kotlinVersionFor(dev.jkbuild.lock.Lockfile lock, JkBuild project) {
        if (lock != null && lock.kotlin() != null && !lock.kotlin().isBlank()) {
            return lock.kotlin();
        }
        if (project != null && project.project().kotlin() instanceof dev.jkbuild.model.VersionSelector.Exact exact) {
            return exact.version();
        }
        return null;
    }

    /**
     * Resolve a Kotlin installation pinned to a specific version (e.g. from a script's {@code
     * //KOTLIN 2.1.0} directive). Passes {@code null} to fall back to the bundled default
     * distribution.
     */
    public static Path resolveKotlinHome(Path cacheDir, String versionOverride) {
        return resolveKotlinHome(cacheDir, versionOverride, NO_NOTICE);
    }

    /**
     * As {@link #resolveKotlinHome(Path, String)}, but reports a one-line provisioning notice
     * ("Linked/Installed Kotlin …") to {@code notice} instead of a stream — the caller (the CLI view,
     * or a phase's {@code PhaseContext::output}) decides how to surface it.
     */
    public static Path resolveKotlinHome(Path cacheDir, String versionOverride, Consumer<String> notice) {
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
            URI uri = URI.create("https://github.com/JetBrains/kotlin/releases/download/v"
                    + versionOverride
                    + "/kotlin-compiler-"
                    + versionOverride
                    + ".zip");
            dist = new ToolDistribution(BuildTool.KOTLIN, versionOverride, uri, "zip");
        }
        try {
            boolean refresh = dev.jkbuild.config.SessionContext.current().config().refreshOr(false);
            ToolProvisioning.Result result =
                    ToolProvisioning.provision(dist, registry, new Http(), /* noDiscover= */ false, refresh);
            switch (result.source()) {
                case LINKED -> notice.accept("Linked Kotlin " + dist.version() + " from " + result.detail());
                case DOWNLOADED -> notice.accept("Installed Kotlin " + dist.version() + " from " + result.detail());
                case CACHED -> {
                    /* silent */
                }
            }
            return result.tool().home();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("failed to provision Kotlin " + dist.version() + ": " + e.getMessage(), e);
        }
    }
}
