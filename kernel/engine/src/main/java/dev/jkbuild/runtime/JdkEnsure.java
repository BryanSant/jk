// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.http.Http;
import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.InstalledJdk;
import dev.jkbuild.jdk.JdkCatalog;
import dev.jkbuild.jdk.JdkCatalogClient;
import dev.jkbuild.jdk.JdkInstaller;
import dev.jkbuild.jdk.JdkLts;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkResolution;
import dev.jkbuild.jdk.JdkSelector;
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
 *
 * <p><b>Note:</b> JDK lifecycle is deliberately decoupled from
 * {@code --no-cache}. Build caches (deps, action cache, git checkouts, tool
 * downloads) get cleared when the user passes the flag; JDK installs do
 * not. JDKs are large (~200 MB), discovered by probes that often share
 * with IntelliJ, and have a dedicated lifecycle under {@code jk jdk
 * install/uninstall}. This file must not accept a {@code noCache}
 * parameter — anything that wants to re-resolve a JDK should remove the
 * install explicitly (e.g. {@code jk jdk uninstall}).
 */
public final class JdkEnsure {

    public enum Source { ALREADY_PINNED, LOCKFILE_INSTALL, INSTALLED }

    public record Outcome(Optional<InstalledJdk> jdk, Source source, String specUsed) {
        public Outcome {
            Objects.requireNonNull(jdk, "jdk");
            Objects.requireNonNull(source, "source");
        }
    }

    private JdkEnsure() {}

    public static Outcome ensure(Path projectDir, Path jdksDirOverride, JkBuild build, Lockfile lock,
                                 java.util.function.Consumer<String> warn)
            throws IOException, InterruptedException {
        JdkRegistry registry = jdksDirOverride != null
                ? new JdkRegistry(jdksDirOverride)
                : new JdkRegistry();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();
        int latestLts = JdkLts.OFFLINE_LATEST_LTS;

        // Walk the one canonical resolution order (--jdk / JK_JDK / .jdk-version /
        // jk.lock / project.jdk / project.java-floor / current / default / env / PATH).
        JdkResolution.Request req = new JdkResolution.Request(
                projectDir, /*switch*/ null, System.getenv("JK_JDK"),
                lock != null ? lock.jdk() : null,
                (build != null && build.project() != null) ? build.project().jdk() : null,
                (build != null && build.project() != null) ? build.project().javaRelease() : 0,
                System::getenv);
        JdkResolution.Resolved r = JdkResolution.resolve(req, registry, defaults, latestLts);

        if (r.jdk().isPresent()) {
            return new Outcome(r.jdk(), mapSource(r.tier()), r.specUsed());
        }
        if (!r.wouldInstall()) {
            // Nothing pinned and nothing to install (resolution found no spec).
            return new Outcome(Optional.empty(), Source.ALREADY_PINNED, null);
        }

        // A named pin (or the bootstrap latest-LTS) isn't on disk — install it.
        if (!HostPlatform.supported()) {
            throw new IOException("host " + System.getProperty("os.name") + "/"
                    + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed (set JAVA_HOME explicitly)");
        }
        String spec = r.installSpec();
        JdkCatalog catalog = new JdkCatalogClient().onWarning(warn).fetch();
        // selectPreferred biases a vendor-unqualified spec to jk's vendor order
        // and resolves range specs (">=26") to the lowest available major.
        Optional<JdkCatalog.Entry> entry = JdkSelector.selectPreferred(
                catalog, spec, HostPlatform.currentOs(), HostPlatform.currentArch());
        if (entry.isEmpty()) {
            throw new IOException("no JDK matches " + spec + " on "
                    + HostPlatform.currentOs() + "/" + HostPlatform.currentArch());
        }
        JdkInstaller installer = new JdkInstaller(new Http(), registry);
        InstalledJdk installed = installer.install(entry.get());

        // A bootstrap install (no JDK was pinned or configured) becomes the
        // de-facto default — but only when no default is set yet, and only for
        // the bootstrap case (a named project pin must not hijack the global
        // default). r.tier() == DEFAULT marks the bootstrap path.
        if (r.tier() == JdkResolution.Tier.DEFAULT && defaults.currentIdentifier().isEmpty()) {
            defaults.set(installed);
        }
        return new Outcome(Optional.of(installed), Source.INSTALLED, spec);
    }

    /** Map a resolution tier to the coarse {@link Source} used for status wording. */
    private static Source mapSource(JdkResolution.Tier tier) {
        return tier == JdkResolution.Tier.LOCKFILE ? Source.LOCKFILE_INSTALL : Source.ALREADY_PINNED;
    }
}
