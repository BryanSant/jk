// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * The single canonical JDK resolution order, shared by the build pipeline ({@code JdkEnsure} /
 * {@code CompileToolchain}) and the {@code jk activate} shell hook ({@code JkEnv}) so they can't
 * diverge. Tiers, highest first:
 *
 * <ol>
 *   <li>{@code --jdk} switch
 *   <li>{@code JK_JDK} env var
 *   <li>{@code .jdk-version} file (strict {@code <vendor>-<major>})
 *   <li>{@code jk.lock} recorded jdk identifier
 *   <li>{@code jk.toml} {@code project.jdk}
 *   <li>{@code project.java} floor — when no jdk is pinned and the requested language level exceeds
 *       the latest LTS, an implied {@code >=<release>}
 *   <li>current ({@link GlobalDefaultJdk} current pointer)
 *   <li>default ({@link GlobalDefaultJdk} default, else the {@link DefaultJdkPolicy} de-facto
 *       default among installed JDKs)
 *   <li>{@code JAVA_HOME}
 *   <li>{@code GRAALVM_HOME}
 *   <li>first {@code javac} on {@code PATH}
 * </ol>
 *
 * <p>{@link #resolve} is for the build path: a <em>named</em> pin (tiers 1-6) that isn't installed
 * stops the walk and reports {@code wouldInstall} so the caller can download it. {@link
 * #resolveForHook} never installs — an uninstalled pin falls through to the lower tiers so an
 * activated shell always gets a working {@code JAVA_HOME}.
 */
public final class JdkResolution {

    public enum Tier {
        SWITCH,
        JK_ENV,
        JDK_VERSION_FILE,
        LOCKFILE,
        PROJECT_TOML,
        JAVA_RELEASE_FLOOR,
        CURRENT,
        DEFAULT,
        JAVA_HOME,
        GRAALVM_HOME,
        PATH,
        NONE
    }

    /** Resolution inputs. Nullable fields mean "tier not applicable". */
    public record Request(
            Path projectDir,
            String switchSpec,
            String envSpec,
            String lockJdkId,
            String projectJdkSpec,
            int projectJavaRelease,
            Function<String, String> env) {
        public Request {
            if (env == null) env = k -> null;
        }
    }

    public record Resolved(
            Optional<InstalledJdk> jdk, Tier tier, String specUsed, boolean wouldInstall, String installSpec) {

        static Resolved found(InstalledJdk jdk, Tier tier, String spec) {
            return new Resolved(Optional.of(jdk), tier, spec, false, null);
        }

        static Resolved install(Tier tier, String spec) {
            return new Resolved(Optional.empty(), tier, spec, true, spec);
        }

        public static final Resolved NONE = new Resolved(Optional.empty(), Tier.NONE, null, false, null);
    }

    private JdkResolution() {}

    /**
     * Build-time resolution: an uninstalled named pin yields {@code wouldInstall}, and the ambient
     * {@code JAVA_HOME}/{@code GRAALVM_HOME}/{@code PATH} are valid last-resort tiers (a build must
     * find <em>some</em> JDK).
     */
    public static Resolved resolve(Request req, JdkRegistry registry, GlobalDefaultJdk defaults, int latestLtsMajor) {
        return walk(req, registry, defaults, latestLtsMajor, true, true);
    }

    /**
     * Resolution for the {@code jk activate} shell hook: never installs and never falls back to the
     * ambient {@code JAVA_HOME}/{@code PATH} — it only activates an explicit jk pin or the (de-facto)
     * default, so outside a jk project with no default the shell keeps its own environment. The
     * de-facto default is LTS-aware via {@link JdkLts#OFFLINE_LATEST_LTS}.
     */
    public static Resolved resolveForHook(Request req, JdkRegistry registry, GlobalDefaultJdk defaults) {
        return walk(req, registry, defaults, JdkLts.OFFLINE_LATEST_LTS, false, false);
    }

    private static Resolved walk(
            Request req,
            JdkRegistry reg,
            GlobalDefaultJdk defaults,
            int latestLtsMajor,
            boolean canInstall,
            boolean envFallback) {
        Resolved r;
        if ((r = named(req.switchSpec(), Tier.SWITCH, reg, canInstall)) != null) return r;
        if ((r = named(req.envSpec(), Tier.JK_ENV, reg, canInstall)) != null) return r;
        if ((r = jdkVersionFile(req.projectDir(), reg, canInstall)) != null) return r;
        if ((r = lockfile(req.lockJdkId(), reg, canInstall)) != null) return r;
        if ((r = named(req.projectJdkSpec(), Tier.PROJECT_TOML, reg, canInstall)) != null) return r;

        // project.java floor: only when nothing is explicitly pinned and the
        // requested language level is newer than the latest LTS — then we need a
        // JDK at least that new (e.g. java = 26 when the latest LTS is 25).
        if ((req.projectJdkSpec() == null || req.projectJdkSpec().isBlank())
                && latestLtsMajor > 0
                && req.projectJavaRelease() > latestLtsMajor) {
            if ((r = named(">=" + req.projectJavaRelease(), Tier.JAVA_RELEASE_FLOOR, reg, canInstall)) != null) {
                return r;
            }
        }

        // current
        Optional<Path> cur = defaults.currentHome();
        if (cur.isPresent() && hasBin(cur.get())) {
            return Resolved.found(installed(cur.get()), Tier.CURRENT, null);
        }

        // default: the exact recorded home wins (unambiguous when two installs
        // share a vendor-major identifier), then the recorded identifier, then
        // the de-facto policy.
        Optional<Path> defHome = defaults.defaultHome();
        if (defHome.isPresent() && hasBin(defHome.get())) {
            return Resolved.found(installed(defHome.get()), Tier.DEFAULT, null);
        }
        try {
            Optional<String> defId = defaults.currentIdentifier();
            if (defId.isPresent()) {
                Optional<InstalledJdk> d = reg.find(defId.get());
                if (d.isPresent()) return Resolved.found(d.get(), Tier.DEFAULT, defId.get());
            }
        } catch (IOException ignored) {
            // malformed config — fall through to the de-facto policy
        }
        Optional<JdkHit> defacto = DefaultJdkPolicy.choose(reg.listHits(), latestLtsMajor);
        if (defacto.isPresent()) {
            return Resolved.found(installed(defacto.get().home()), Tier.DEFAULT, null);
        }

        // Ambient JAVA_HOME / GRAALVM_HOME / PATH — only for the build path; the
        // shell hook must not re-export the shell's own JDK as a jk activation.
        if (envFallback) {
            if ((r = envHome(req.env().apply("JAVA_HOME"), Tier.JAVA_HOME)) != null) return r;
            if ((r = envHome(req.env().apply("GRAALVM_HOME"), Tier.GRAALVM_HOME)) != null) return r;
            Optional<Path> onPath = ActiveJavac.home();
            if (onPath.isPresent() && hasBin(onPath.get())) {
                return Resolved.found(installed(onPath.get()), Tier.PATH, null);
            }
            // Nothing on disk at all → bootstrap-install the latest LTS (which
            // then becomes the default).
            if (canInstall && latestLtsMajor > 0 && reg.listHits().isEmpty()) {
                return Resolved.install(Tier.DEFAULT, "temurin-" + latestLtsMajor);
            }
        }
        return Resolved.NONE;
    }

    /** A named-spec tier: resolve on disk; else (build) signal install, else (hook) continue. */
    private static Resolved named(String spec, Tier tier, JdkRegistry reg, boolean canInstall) {
        if (spec == null || spec.isBlank()) return null;
        Optional<InstalledJdk> hit = reg.findBySpec(spec);
        if (hit.isPresent()) return Resolved.found(hit.get(), tier, spec);
        return canInstall ? Resolved.install(tier, spec) : null;
    }

    private static Resolved jdkVersionFile(Path dir, JdkRegistry reg, boolean canInstall) {
        if (dir == null) return null;
        Optional<String> pin;
        try {
            pin = JdkResolver.readJdkVersion(dir);
        } catch (IOException e) {
            return null;
        }
        if (pin.isEmpty()) return null;
        String spec;
        try {
            spec = JdkResolver.validatePin(pin.get());
        } catch (IllegalArgumentException e) {
            return null; // malformed .jdk-version → skip this tier
        }
        return named(spec, Tier.JDK_VERSION_FILE, reg, canInstall);
    }

    private static Resolved lockfile(String lockId, JdkRegistry reg, boolean canInstall) {
        if (lockId == null || lockId.isBlank()) return null;
        try {
            Optional<InstalledJdk> hit = reg.find(lockId);
            if (hit.isPresent()) return Resolved.found(hit.get(), Tier.LOCKFILE, lockId);
        } catch (IOException ignored) {
            // unreadable — treat as uninstalled
        }
        return canInstall ? Resolved.install(Tier.LOCKFILE, lockId) : null;
    }

    private static Resolved envHome(String home, Tier tier) {
        if (home == null || home.isBlank()) return null;
        Path p = Path.of(home);
        if (!hasBin(p)) return null;
        return Resolved.found(installed(p), tier, null);
    }

    private static boolean hasBin(Path home) {
        return home != null && Files.isDirectory(home.resolve("bin"));
    }

    private static InstalledJdk installed(Path home) {
        return new InstalledJdk(JdkRegistry.identifierFor(home), home);
    }
}
