// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.jdk.GlobalDefaultJdk;
import dev.jkbuild.jdk.JdkRegistry;
import dev.jkbuild.jdk.JdkVendor;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Computes the per-directory environment {@code jk hook-env} should expose.
 *
 * <p>Walks up from a starting directory to find the nearest {@code jk.toml};
 * if found, consults the sibling {@code jk.lock} for the resolved JDK
 * identifier and looks up the install's {@code JAVA_HOME} (and
 * {@code GRAALVM_HOME} when the install is a GraalVM distribution).
 *
 * <p>The PATH prepend is rendered against {@code __JK_ORIG_PATH} (set by the
 * activation script on first source) so re-sourcing the activate script
 * doesn't accumulate duplicate entries.
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
     * Resolve the desired env for a {@code cwd}. The project's pinned JDK (from
     * the nearest {@code jk.toml}'s {@code jk.lock}) wins; failing that, the
     * global <em>current</em> JDK, then the <em>default</em> JDK, so {@code
     * java} / {@code javac} resolve to a jk-managed JDK in any activated shell.
     * Empty only when nothing is pinned and no default is configured. Carries
     * JAVA_HOME / GRAALVM_HOME / PATH plus the project root (when a
     * {@code jk.toml} was found upstream) for {@code hook-env} status output.
     */
    public Target resolve(Path cwd) throws IOException {
        var root = findProjectRoot(cwd);
        var jdk = projectPinnedJdk(root);
        if (jdk.isEmpty()) {
            // No project pin — honour the global current, then default JDK.
            jdk = currentOrDefaultJdk();
        }
        if (jdk.isEmpty()) return Target.empty();
        return targetFor(root, jdk.get());
    }

    /** The JDK the project at {@code root} pins via its sibling {@code jk.lock}, if any. */
    private Optional<ResolvedJdk> projectPinnedJdk(Optional<Path> root) throws IOException {
        if (root.isEmpty()) return Optional.empty();
        var lockPath = root.get().resolve("jk.lock");
        if (!Files.isRegularFile(lockPath)) return Optional.empty();
        Lockfile lock;
        try {
            lock = LockfileReader.read(lockPath);
        } catch (RuntimeException e) {
            // Corrupt lockfile — fail soft; jk hook-env never blocks the shell.
            return Optional.empty();
        }
        var jdkId = lock.jdk();
        if (jdkId == null || jdkId.isBlank()) return Optional.empty();
        return lookupJdkHome(jdkId);
    }

    /**
     * The global fallback JDK when no project pins one: the {@code current-jdk}
     * pointer if it resolves, else the configured {@code default-jdk}. Fails
     * soft (empty) on a missing/unreadable config so the shell is never blocked.
     */
    private Optional<ResolvedJdk> currentOrDefaultJdk() throws IOException {
        var currentHome = globalDefault.currentHome();
        if (currentHome.isPresent() && Files.isDirectory(currentHome.get().resolve("bin"))) {
            var home = currentHome.get();
            return Optional.of(new ResolvedJdk(home, matchVendor(home)));
        }
        Optional<String> defaultId;
        try {
            defaultId = globalDefault.currentIdentifier();
        } catch (IOException malformedConfig) {
            return Optional.empty();
        }
        return defaultId.isPresent() ? lookupJdkHome(defaultId.get()) : Optional.empty();
    }

    /** Build the env vars (JAVA_HOME / GRAALVM_HOME / PATH) for a resolved JDK. */
    private Target targetFor(Optional<Path> root, ResolvedJdk jdk) {
        var vars = new LinkedHashMap<String, String>();
        var home = jdk.home();
        vars.put(JAVA_HOME, home.toString());
        if (isGraalvm(jdk)) {
            vars.put(GRAALVM_HOME, home.toString());
        }
        var bin = home.resolve("bin").toString();
        vars.put(PATH, bin + File.pathSeparator + origPath);
        return new Target(root, vars);
    }

    /**
     * Look up a JDK install by the identifier stored in {@code jk.lock}.
     * {@link JdkRegistry#find} handles both jk-managed installs (short
     * identifier like {@code temurin-25.0.3}) and external installs (whose
     * identifier is the basename of the install dir).
     */
    Optional<ResolvedJdk> lookupJdkHome(String id) throws IOException {
        // Absolute-path identifier — used by legacy lockfiles that stamped
        // the home path directly. Honour it if the home is still on disk.
        if (id.startsWith("/") || id.startsWith("\\") || isWindowsAbsolute(id)) {
            var p = Path.of(id);
            if (Files.isDirectory(p.resolve("bin"))) {
                return Optional.of(new ResolvedJdk(p, matchVendor(p)));
            }
        }
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
        return s.length() >= 3 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':'
                && (s.charAt(2) == '\\' || s.charAt(2) == '/');
    }

    public record ResolvedJdk(Path home, JdkVendor vendor) {}

    /**
     * Target env state for a given cwd.
     *
     * @param projectRoot present iff a {@code jk.toml} was found upstream
     * @param vars the env keys + values jk should ensure are set; an empty map
     *     means "no project — restore originals"
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
