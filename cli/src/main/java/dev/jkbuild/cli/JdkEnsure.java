// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli;

import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkResolver;
import dev.jkbuild.jdk.JdkSelector;
import dev.jkbuild.jdk.JdkSpec;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * "Make a JDK available for this project" — the resolve-or-install step that
 * {@code jk sync} runs before (or in parallel with) dependency fetching.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>An already-installed JDK keyed on the project's {@code .jdk-version}
 *       (via {@link JdkResolver#forProject}).</li>
 *   <li>The {@code jdk} stamped in {@code jk.lock}, looked up by install
 *       identifier in the local registry.</li>
 *   <li>If neither resolves, derive a {@link JdkSpec} from the lockfile or
 *       {@code jk.toml}'s {@code [project].jdk} and install via the JetBrains
 *       JDK feed.</li>
 * </ol>
 *
 * <p>Returns {@link Outcome} describing what happened — the resolved JDK plus
 * a flag for whether an install actually ran — so callers can word their
 * status output accordingly.
 */
final class JdkEnsure {

    enum Source { ALREADY_PINNED, LOCKFILE_INSTALL, INSTALLED }

    record Outcome(Optional<InstalledJdk> jdk, Source source, String specUsed) {
        Outcome {
            Objects.requireNonNull(jdk, "jdk");
            Objects.requireNonNull(source, "source");
        }
    }

    private JdkEnsure() {}

    static Outcome ensure(Path projectDir, Path jdksDirOverride, JkBuild build, Lockfile lock)
            throws IOException, InterruptedException {
        // 1. Already pinned via .jdk-version → use what's there.
        Optional<InstalledJdk> resolved = JdkResolver.forProject(projectDir, jdksDirOverride);
        if (resolved.isPresent()) {
            return new Outcome(resolved, Source.ALREADY_PINNED, null);
        }

        // 2. Lockfile recorded a specific install identifier — see if it's
        //    sitting in the registry.
        JdkRegistry registry = jdksDirOverride != null
                ? new JdkRegistry(jdksDirOverride)
                : new JdkRegistry();
        if (lock != null && lock.jdk() != null && !lock.jdk().isBlank()) {
            Optional<InstalledJdk> byId = registry.find(lock.jdk());
            if (byId.isPresent()) {
                return new Outcome(byId, Source.LOCKFILE_INSTALL, lock.jdk());
            }
        }

        // 3. Install. Pick a spec: lockfile id (treated as a bare identifier)
        //    if present, otherwise the major version from jk.toml. If neither
        //    is available we can't decide what to install — return empty and
        //    let the caller fall back to JAVA_HOME.
        String spec = chooseSpec(build, lock);
        if (spec == null) {
            return new Outcome(Optional.empty(), Source.ALREADY_PINNED, null);
        }
        if (!HostPlatform.supported()) {
            throw new IOException("host " + System.getProperty("os.name") + "/"
                    + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed (set JAVA_HOME explicitly)");
        }

        JdkCatalog catalog = new JdkCatalogClient().fetch();
        Optional<JdkCatalog.Entry> entry = JdkSelector.select(
                catalog, JdkSpec.parse(spec), HostPlatform.currentOs(), HostPlatform.currentArch());
        if (entry.isEmpty()) {
            throw new IOException("no JDK matches " + spec + " on "
                    + HostPlatform.currentOs() + "/" + HostPlatform.currentArch());
        }
        JdkInstaller installer = new JdkInstaller(new Http(), registry);
        // Catalog produced an entry that already maps to an on-disk install
        // — no download needed.
        InstalledJdk already = installer.alreadyInstalled(entry.get());
        if (already != null) {
            return new Outcome(Optional.of(already), Source.ALREADY_PINNED, spec);
        }
        InstalledJdk installed = installer.install(entry.get());
        return new Outcome(Optional.of(installed), Source.INSTALLED, spec);
    }

    private static String chooseSpec(JkBuild build, Lockfile lock) {
        if (lock != null && lock.jdk() != null && !lock.jdk().isBlank()) {
            return lock.jdk();
        }
        if (build != null && build.project() != null && build.project().jdk() > 0) {
            return String.valueOf(build.project().jdk());
        }
        return null;
    }
}
