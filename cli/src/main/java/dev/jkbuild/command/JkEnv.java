// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Computes the per-directory environment {@code jk hook-env} should expose.
 *
 * <p>Walks up from a starting directory to find the nearest {@code jk.toml}; if found, consults the
 * sibling {@code jk.lock} for the resolved JDK identifier and looks up the install's {@code
 * JAVA_HOME} (and {@code GRAALVM_HOME} when the install is a GraalVM distribution).
 *
 * <p>The PATH prepend is rendered against {@code __JK_ORIG_PATH} (set by the activation script on
 * first source) so re-sourcing the activate script doesn't accumulate duplicate entries.
 */
public final class JkEnv {

    /** The environment variables {@code jk activate} manages. */
    public static final String JAVA_HOME = "JAVA_HOME";

    public static final String GRAALVM_HOME = "GRAALVM_HOME";
    public static final String PATH = "PATH";

    private final JdkRegistry registry;
    private final String origPath;
    private final GlobalDefaultJdk globalDefault;

    public JkEnv(JdkRegistry registry, String origPath) {
        this(registry, origPath, GlobalDefaultJdk.current());
    }

    public JkEnv(JdkRegistry registry, String origPath, GlobalDefaultJdk globalDefault) {
        this.registry = registry;
        this.origPath = origPath == null ? "" : origPath;
        this.globalDefault = globalDefault;
    }

    /** Real-world entry: uses the default probe chain and reads {@code __JK_ORIG_PATH}. */
    public static JkEnv defaults() {
        var origPath = System.getenv("__JK_ORIG_PATH");
        if (origPath == null) origPath = System.getenv("PATH");
        return new JkEnv(new JdkRegistry(), origPath, GlobalDefaultJdk.current());
    }

    /**
     * Resolve the desired env for a {@code cwd} via the one canonical JDK order ({@link
     * dev.jkbuild.jdk.JdkResolution}): {@code JK_JDK} env, the project's {@code .jdk-version} /
     * {@code jk.lock} / {@code project.jdk}, then the global current / default JDK, then {@code
     * JAVA_HOME} / {@code GRAALVM_HOME} / {@code PATH}. Never installs (the hook must not block the
     * shell). Empty only when nothing resolves. Carries JAVA_HOME / GRAALVM_HOME / PATH plus the
     * project root (when a {@code jk.toml} was found upstream).
     */
    public Target resolve(Path cwd) throws IOException {
        var root = findProjectRoot(cwd);
        // Shell-hook fast path (docs/thin-client-plan.md): this resolve runs on EVERY
        // prompt — no engine call, no tomlj parse. TomlScan reads exactly the scalars the
        // JDK resolution chain needs; anything it can't see reads as absent (fail-soft,
        // same as an unparseable jk.toml before).
        String lockId = lockJdkId(root);
        String projectJdk = null;
        String projectGraal = null;
        int javaRelease = 0;
        if (root.isPresent()) {
            var scan = dev.jkbuild.config.TomlScan.scan(
                    root.get().resolve("jk.toml"), "project.jdk", "project.java", "native.graal");
            projectJdk = scan.get("project.jdk");
            javaRelease = scan.getInt("project.java", 0);
            // [native] present without an explicit graal spec defaults to "graalvm" —
            // mirror JkBuildParser.parseNativeConfig.
            projectGraal = scan.get("native.graal");
            if (projectGraal == null && scan.hasSection("native")) projectGraal = "graalvm";
        }
        var req = new dev.jkbuild.jdk.JdkResolution.Request(
                root.orElse(cwd), /*switch*/
                null,
                System.getenv("JK_JDK"),
                lockId,
                projectJdk,
                javaRelease,
                System::getenv);
        var resolved = dev.jkbuild.jdk.JdkResolution.resolveForHook(req, registry, globalDefault);
        if (resolved.jdk().isEmpty()) return Target.empty();
        var home = resolved.jdk().get().home();
        var jdk = new ResolvedJdk(home, matchVendor(home));
        return targetFor(root, jdk, resolveGraalHome(projectGraal, jdk));
    }

    /** The jdk identifier the project at {@code root} pins via its {@code jk.lock}, if any. */
    private String lockJdkId(Optional<Path> root) {
        if (root.isEmpty()) return null;
        var lockPath = root.get().resolve("jk.lock");
        if (!Files.isRegularFile(lockPath)) return null;
        // Line scan, not LockfileReader: the lockfile can be large and this runs per prompt;
        // its top-level `jdk` scalar sits in the machine-written header.
        String id = dev.jkbuild.config.TomlScan.scan(lockPath, "jdk").get("jdk");
        return (id == null || id.isBlank()) ? null : id;
    }

    /**
     * Build the env vars (JAVA_HOME / GRAALVM_HOME / PATH). GRAALVM_HOME is managed independently of
     * JAVA_HOME from its own chain ({@code JK_GRAAL} > {@code project.graal} > the {@code jk jdk
     * graal} default); only when that chain finds nothing do we fall back to the active JDK if it is
     * itself a GraalVM. Absent → the hook unsets any GRAALVM_HOME it previously exported.
     */
    private Target targetFor(Optional<Path> root, ResolvedJdk jdk, Optional<Path> graalHome) {
        var vars = new LinkedHashMap<String, String>();
        var home = jdk.home();
        vars.put(JAVA_HOME, home.toString());
        if (graalHome.isPresent()) {
            vars.put(GRAALVM_HOME, graalHome.get().toString());
        } else if (isGraalvm(jdk)) {
            vars.put(GRAALVM_HOME, home.toString());
        }
        var bin = home.resolve("bin").toString();
        vars.put(PATH, bin + File.pathSeparator + origPath);
        return new Target(root, vars);
    }

    /**
     * Resolve the default GraalVM home, independent of JAVA_HOME: {@code JK_GRAAL} > {@code
     * project.graal} > the {@code jk jdk graal} default pointer. The {@code native} keyword (or no
     * match) means "the newest installed GraalVM". Empty when no GraalVM is configured/installed.
     */
    private Optional<Path> resolveGraalHome(String projectGraalSpec, ResolvedJdk javaJdk) {
        for (String spec : new String[] {System.getenv("JK_GRAAL"), projectGraalSpec}) {
            if (spec == null || spec.isBlank()) continue;
            if (spec.trim().equalsIgnoreCase("native")) {
                Optional<Path> g = newestInstalledGraal();
                if (g.isPresent()) return g;
                continue;
            }
            Optional<dev.jkbuild.jdk.JdkHit> hit = registry.findHitBySpec(spec).filter(JkEnv::isGraalVendor);
            if (hit.isPresent()) return Optional.of(hit.get().home());
        }
        // The `jk jdk graal` default pointer (symlink, then config identifier).
        Optional<Path> ghome = globalDefault.graalHome();
        if (ghome.isPresent() && Files.isDirectory(ghome.get().resolve("bin"))) return ghome;
        try {
            Optional<String> gid = globalDefault.graalIdentifier();
            if (gid.isPresent()) {
                var m = registry.find(gid.get());
                if (m.isPresent()) return Optional.of(m.get().home());
            }
        } catch (IOException ignored) {
            // malformed config — no graal default
        }
        return Optional.empty();
    }

    private Optional<Path> newestInstalledGraal() {
        return registry.listHits().stream()
                .filter(JkEnv::isGraalVendor)
                .sorted(Comparator.comparingInt((dev.jkbuild.jdk.JdkHit h) -> {
                            int i = JdkVendor.GRAAL_PREFERENCE.indexOf(h.vendor());
                            return i >= 0 ? i : Integer.MAX_VALUE;
                        })
                        .thenComparing(
                                h -> h.version() == null ? "" : dev.jkbuild.jdk.JdkSelector.versionKey(h.version()),
                                Comparator.reverseOrder()))
                .map(dev.jkbuild.jdk.JdkHit::home)
                .findFirst();
    }

    private static boolean isGraalVendor(dev.jkbuild.jdk.JdkHit h) {
        return h.vendor() == JdkVendor.ORACLE_GRAALVM || h.vendor() == JdkVendor.GRAALVM_CE;
    }

    /**
     * Look up a JDK install by the identifier stored in {@code jk.lock}. {@link JdkRegistry#find}
     * handles both jk-managed installs (short identifier like {@code temurin-25.0.3}) and external
     * installs (whose identifier is the basename of the install dir).
     */
    Optional<ResolvedJdk> lookupJdkHome(String id) throws IOException {
        var managed = registry.find(id);
        if (managed.isPresent()) {
            var home = managed.get().home();
            return Optional.of(new ResolvedJdk(home, matchVendor(home)));
        }
        // Fallback for identifiers that no longer round-trip through find()
        // (e.g. a probe vanished since the lockfile was written): scan hits
        // by basename.
        for (var hit : registry.listHits()) {
            if (hit.home().getFileName() != null
                    && hit.home().getFileName().toString().equals(id)) {
                return Optional.of(new ResolvedJdk(hit.home(), hit.vendor()));
            }
        }
        return Optional.empty();
    }

    /** Walk up from {@code cwd} until a {@code jk.toml} is found or root is reached. */
    static Optional<Path> findProjectRoot(Path cwd) {
        if (cwd == null) return Optional.empty();
        var p = cwd.toAbsolutePath().normalize();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("jk.toml"))) return Optional.of(p);
            p = p.getParent();
        }
        return Optional.empty();
    }

    private JdkVendor matchVendor(Path home) {
        // Look first in the probe-emitted hits (cheap, already parsed); fall
        // back to reading the release file directly. Either way, UNKNOWN if
        // we can't tell — the GraalVM detection just won't fire.
        try {
            for (var hit : registry.listHits()) {
                if (hit.home().equals(home)) return hit.vendor();
            }
        } catch (RuntimeException ignored) {
            // listHits() may swallow IO already; fall through to release-file probe
        }
        try {
            return JdkVendor.fromRelease(home);
        } catch (RuntimeException ignored) {
            return JdkVendor.UNKNOWN;
        }
    }

    private static boolean isGraalvm(ResolvedJdk jdk) {
        var v = jdk.vendor();
        return v == JdkVendor.ORACLE_GRAALVM || v == JdkVendor.GRAALVM_CE;
    }

    private static boolean isWindowsAbsolute(String s) {
        return s.length() >= 3
                && Character.isLetter(s.charAt(0))
                && s.charAt(1) == ':'
                && (s.charAt(2) == '\\' || s.charAt(2) == '/');
    }

    public record ResolvedJdk(Path home, JdkVendor vendor) {}

    /**
     * Target env state for a given cwd.
     *
     * @param projectRoot present iff a {@code jk.toml} was found upstream
     * @param vars the env keys + values jk should ensure are set; an empty map means "no project —
     *     restore originals"
     */
    public record Target(Optional<Path> projectRoot, Map<String, String> vars) {

        public Target {
            vars = Map.copyOf(vars);
        }

        public static Target empty() {
            return new Target(Optional.empty(), Map.of());
        }

        public boolean isActive() {
            return !vars.isEmpty();
        }
    }
}
