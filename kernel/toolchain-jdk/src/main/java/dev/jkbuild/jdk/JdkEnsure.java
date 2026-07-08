// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.http.Http;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.model.JkBuild;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * "Make a JDK available for this project" — the resolve-or-install step that {@code jk sync} runs
 * before (or in parallel with) dependency fetching.
 *
 * <p>Resolution is delegated to {@link dev.jkbuild.jdk.JdkResolution}, the one canonical order
 * shared with the compile toolchain and the {@code jk activate} shell hook ({@code --jdk} / {@code
 * JK_JDK} / {@code .jdk-version} / {@code jk.lock} / {@code [project].jdk} / {@code project.java}
 * floor / current / default / {@code JAVA_HOME} / {@code GRAALVM_HOME} / {@code PATH}). When a
 * named pin isn't installed — or no JDK exists at all — this step installs from the JetBrains feed;
 * a bootstrap install (nothing was configured) becomes the persisted default. See {@code
 * docs/jdk-resolution.md}.
 *
 * <p>Returns {@link Outcome} describing what happened — the resolved JDK plus a flag for whether an
 * install actually ran — so callers can word their status output accordingly.
 *
 * <p><b>Note:</b> JDK lifecycle is deliberately decoupled from {@code --force}. Build caches
 * (deps, action cache, git checkouts, tool downloads) get bypassed when the user passes the flag;
 * JDK installs do not. JDKs are large (~200 MB), discovered by probes that often share with
 * IntelliJ, and have a dedicated lifecycle under {@code jk jdk install/uninstall}. This file must
 * not accept a {@code refresh} parameter — anything that wants to re-resolve a JDK should remove
 * the install explicitly (e.g. {@code jk jdk uninstall}).
 */
public final class JdkEnsure {

    public enum Source {
        ALREADY_PINNED,
        LOCKFILE_INSTALL,
        INSTALLED
    }

    public record Outcome(Optional<InstalledJdk> jdk, Source source, String specUsed) {
        public Outcome {
            Objects.requireNonNull(jdk, "jdk");
            Objects.requireNonNull(source, "source");
        }
    }

    private JdkEnsure() {}

    public static Outcome ensure(
            Path projectDir,
            Path jdksDirOverride,
            JkBuild build,
            Lockfile lock,
            java.util.function.Consumer<String> warn)
            throws IOException, InterruptedException {
        return ensure(projectDir, jdksDirOverride, build, lock, warn, true);
    }

    /**
     * As {@link #ensure(Path, Path, JkBuild, Lockfile, java.util.function.Consumer)}, but with an
     * explicit install permission. {@code allowInstall = false} is the resident engine's mode
     * ({@code jk sync} hosting): a JDK download must never happen silently inside the engine —
     * installs stay client-side ({@code jk jdk install}, or the sync command's pre-flight ensure),
     * per {@code docs/engine.md} — so a resolution that would install instead fails with an
     * actionable message naming the client-side fix.
     */
    public static Outcome ensure(
            Path projectDir,
            Path jdksDirOverride,
            JkBuild build,
            Lockfile lock,
            java.util.function.Consumer<String> warn,
            boolean allowInstall)
            throws IOException, InterruptedException {
        JdkRegistry registry = jdksDirOverride != null ? new JdkRegistry(jdksDirOverride) : new JdkRegistry();
        GlobalDefaultJdk defaults = GlobalDefaultJdk.current();
        int latestLts = JdkLts.OFFLINE_LATEST_LTS;

        // Walk the one canonical resolution order (--jdk / JK_JDK / .jdk-version /
        // jk.lock / project.jdk / project.java-floor / current / default / env / PATH).
        JdkResolution.Request req = new JdkResolution.Request(
                projectDir,
                dev.jkbuild.config.SessionContext.current().jdkSpec(),
                System.getenv("JK_JDK"),
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
        if (!allowInstall) {
            // Engine-hosted sync: the client should have pre-flighted this install before sending
            // the request — reaching here means it didn't (or the lock was created engine-side with
            // a pin the client couldn't see). Fail structured rather than downloading silently.
            throw new IOException("JDK " + r.installSpec()
                    + " is not installed — run `jk jdk install " + r.installSpec()
                    + "` (or re-run `jk sync`; JDK installs stay client-side)");
        }

        // A named pin (or the bootstrap latest-LTS) isn't on disk — install it.
        String spec = r.installSpec();
        InstalledJdk installed = install(spec, registry, warn);

        // A bootstrap install (no JDK was pinned or configured) becomes the
        // de-facto default — but only when no default is set yet, and only for
        // the bootstrap case (a named project pin must not hijack the global
        // default). r.tier() == DEFAULT marks the bootstrap path.
        if (r.tier() == JdkResolution.Tier.DEFAULT
                && defaults.currentIdentifier().isEmpty()) {
            defaults.set(installed);
        }
        return new Outcome(Optional.of(installed), Source.INSTALLED, spec);
    }

    /** Map a resolution tier to the coarse {@link Source} used for status wording. */
    private static Source mapSource(JdkResolution.Tier tier) {
        return tier == JdkResolution.Tier.LOCKFILE ? Source.LOCKFILE_INSTALL : Source.ALREADY_PINNED;
    }

    /**
     * Install exactly {@code spec} into the default registry, with no resolution walk and no
     * default-JDK side effects. The engine-host bootstrap uses this to satisfy jk's own runtime
     * floor without consulting (or disturbing) the user's project pins and global default.
     */
    public static InstalledJdk install(String spec, java.util.function.Consumer<String> warn)
            throws IOException, InterruptedException {
        return install(spec, new JdkRegistry(), warn);
    }

    private static InstalledJdk install(String spec, JdkRegistry registry, java.util.function.Consumer<String> warn)
            throws IOException, InterruptedException {
        if (!HostPlatform.supported()) {
            throw new IOException("host "
                    + System.getProperty("os.name")
                    + "/"
                    + System.getProperty("os.arch")
                    + " is not covered by the JetBrains JDK feed (set JAVA_HOME explicitly)");
        }
        JdkCatalog catalog = new JdkCatalogClient().onWarning(warn).fetch();
        // selectPreferred biases a vendor-unqualified spec to jk's vendor order
        // and resolves range specs (">=26") to the lowest available major.
        Optional<JdkCatalog.Entry> entry =
                JdkSelector.selectPreferred(catalog, spec, HostPlatform.currentOs(), HostPlatform.currentArch());
        if (entry.isEmpty()) {
            throw new IOException(
                    "no JDK matches " + spec + " on " + HostPlatform.currentOs() + "/" + HostPlatform.currentArch());
        }
        return new JdkInstaller(new Http(), registry).install(entry.get());
    }
}
