// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.cli.theme.Theme;
import dev.jkbuild.cli.tui.Confirm;
import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkKeywords;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkResolver;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.tool.NativeImageDriver;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the GraalVM home whose {@code bin/native-image} {@code jk native} (and {@code jk
 * install} of a native app) uses. Resolution mirrors {@code project.jdk}:
 *
 * <ol>
 *   <li>{@code project.graal} set → an already-installed GraalVM matching the spec, else
 *       auto-install it (no prompt — the pin is explicit intent, same as {@code project.jdk}).
 *   <li>{@code project.graal} unset → the current {@code native-image} search (project JDK → {@code
 *       $GRAALVM_HOME} → {@code PATH}).
 *   <li>Still missing → offer to install Oracle GraalVM: prompt with the {@link Confirm} widget on
 *       a TTY, install silently with {@code --yes}, or fail with an actionable hint on a non-TTY.
 * </ol>
 *
 * <p>Must run <em>before</em> the progress UI ({@code CommandManager}) opens — prompting/installing
 * inside a captured-output region corrupts the display. Results are memoized by spec so a workspace
 * with many native modules fetches the catalog and installs at most once per distinct spec.
 */
public final class GraalResolver {

    private final Path jdksDir; // nullable — overrides the default jdks root
    private final boolean assumeYes; // --yes: install without prompting
    private final Map<String, Path> memo = new HashMap<>();

    public GraalResolver(Path jdksDir, boolean assumeYes) {
        this.jdksDir = jdksDir;
        this.assumeYes = assumeYes;
    }

    /**
     * The GraalVM home to use for {@code projectDir}, or empty when it couldn't be resolved (an
     * actionable message has already been printed — the caller should abort the native build). A
     * non-empty result is suitable to pass as {@code graalHome} to {@code BuildPipeline.nativePhase}.
     */
    public Optional<Path> resolve(Path projectDir, String graalSpec) {
        String key = graalSpec == null ? "" : graalSpec;
        if (memo.containsKey(key)) {
            return Optional.ofNullable(memo.get(key));
        }
        Path home = resolveUncached(projectDir, graalSpec);
        memo.put(key, home);
        return Optional.ofNullable(home);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private Path resolveUncached(Path projectDir, String graalSpec) {
        JdkRegistry registry = jdksDir != null ? new JdkRegistry(jdksDir) : new JdkRegistry();

        // 1. Explicit spec: --graal switch (jk.graal) > project.graal > JK_GRAAL env.
        String effective = firstNonBlank(System.getProperty("jk.graal"), graalSpec, System.getenv("JK_GRAAL"));
        if (effective != null && !effective.isBlank()) {
            Optional<InstalledJdk> hit = registry.findBySpec(effective);
            if (hit.isPresent() && NativeImageDriver.resolve(hit.get().home()).isPresent()) {
                return hit.get().home();
            }
            return install(effective, registry, /*announce*/ "graal = \"" + effective + "\"");
        }

        // 2. The `jk jdk graal` default-graal pointer, if one is set and usable.
        try {
            dev.jkbuild.jdk.GlobalDefaultJdk gd = dev.jkbuild.jdk.GlobalDefaultJdk.current();
            Optional<Path> gh = gd.graalHome();
            if (gh.isPresent() && NativeImageDriver.resolve(gh.get()).isPresent()) {
                return gh.get();
            }
            Optional<String> gid = gd.graalIdentifier();
            if (gid.isPresent()) {
                Optional<InstalledJdk> byId = registry.find(gid.get());
                if (byId.isPresent()
                        && NativeImageDriver.resolve(byId.get().home()).isPresent()) {
                    return byId.get().home();
                }
            }
        } catch (IOException ignored) {
            // no usable default-graal — fall through to the ambient search
        }

        // 3. No pin/default — current native-image search (project JDK → $GRAALVM_HOME → PATH).
        // projectJavaHome may be null (jk runs as a native image with no java.home,
        // and the project pins no JDK); NativeImageDriver.resolve tolerates null and
        // still checks $GRAALVM_HOME and PATH.
        Path projectJavaHome = null;
        try {
            projectJavaHome = JdkResolver.forProject(projectDir, jdksDir)
                    .map(InstalledJdk::home)
                    .orElse(null);
        } catch (IOException ignored) {
            // fall through with null
        }
        if (projectJavaHome == null) {
            String javaHome = System.getProperty("java.home");
            if (javaHome != null && !javaHome.isBlank()) projectJavaHome = Path.of(javaHome);
        }
        Optional<Path> binary = NativeImageDriver.resolve(projectJavaHome);
        if (binary.isPresent()) {
            // Hand the phase the GraalVM home that owns native-image (.../bin/native-image).
            Path bin = binary.get();
            return bin.getParent() != null && bin.getParent().getParent() != null
                    ? bin.getParent().getParent()
                    : projectJavaHome;
        }

        // 3. Missing — offer Oracle GraalVM (prompt / --yes / non-TTY fail).
        return offerOracleGraalVm(projectJavaHome, registry);
    }

    private Path offerOracleGraalVm(Path searchedJavaHome, JdkRegistry registry) {
        if (!assumeYes && !Confirm.isInteractiveTerminal()) {
            // Can't prompt — fail with the same actionable hint as the driver.
            System.err.println(NativeImageDriver.notFoundError(searchedJavaHome).getMessage());
            System.err.println("  Or pin a GraalVM with `graal = \"native\"` under [project], "
                    + "or pass --yes to install Oracle GraalVM automatically.");
            return null;
        }
        if (!assumeYes) {
            String warn = Theme.colorize("‼", Theme.active().warning());
            boolean ok = Confirm.of(
                            warn + " native-image not found. " + "Install Oracle GraalVM to build native artifacts?",
                            true)
                    .ask();
            if (!ok) {
                System.err.println("Aborted — no GraalVM to build with. Pin one with "
                        + "`graal = \"native\"` or install: jk jdk install native");
                return null;
            }
        }
        return install("native", registry, /*announce*/ "Oracle GraalVM");
    }

    /** Resolve {@code spec} (keyword-aware) to a catalog entry and install it. */
    private Path install(String spec, JdkRegistry registry, String announce) {
        String os = HostPlatform.currentOs();
        String arch = HostPlatform.currentArch();
        if (!HostPlatform.supported()) {
            System.err.println("jk native: this host ("
                    + os
                    + "/"
                    + arch
                    + ") has no installable GraalVM; set $GRAALVM_HOME instead.");
            return null;
        }
        try {
            JdkCatalog catalog = new JdkCatalogClient().fetch();
            String effective = spec;
            if (JdkKeywords.isKeyword(spec)) {
                effective =
                        JdkKeywords.resolveToMajorSpec(catalog, spec, os, arch).orElse(spec);
            }
            Optional<JdkCatalog.Entry> entry = JdkSelector.selectPreferred(catalog, effective, os, arch);
            if (entry.isEmpty()) {
                System.err.println("jk native: no GraalVM matches " + spec + " on " + os + "/" + arch + ".");
                return null;
            }
            JdkCatalog.Entry e = entry.get();
            System.out.println(Theme.colorize("⬇", Theme.active().cyan())
                    + " Installing GraalVM "
                    + Theme.colorize(e.installFolderName(), Theme.active().focused())
                    + " ("
                    + announce
                    + ")…");
            InstalledJdk installed = new JdkInstaller(new Http(), registry).install(e);
            System.out.println(Theme.colorize("✓", Theme.active().success()) + " GraalVM ready: " + installed.home());
            return installed.home();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            System.err.println("jk native: GraalVM install interrupted.");
            return null;
        } catch (Exception ex) {
            System.err.println("jk native: failed to install GraalVM (" + spec + "): " + ex.getMessage());
            return null;
        }
    }
}
