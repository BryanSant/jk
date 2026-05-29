// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves the on-disk directories jk uses for config, cache, state, data,
 * tool launchers, and installed JDKs. Cargo-style flat layout under a
 * single root, identical on Linux, macOS, and Windows:
 *
 * <pre>
 *   ~/.jk/
 *   ├── config.toml      # user config (was ~/.config/jk.toml)
 *   ├── cache/           # downloads + content-addressed action cache
 *   ├── state/           # mutable per-host state
 *   ├── data/            # immutable installed data (ledgers, registries)
 *   ├── bin/             # user-installed tool launchers
 *   └── jdks/            # JDK installs
 * </pre>
 *
 * <p>Overrides, highest precedence first:
 * <ul>
 *   <li>Per-directory: {@code JK_CONFIG_FILE}, {@code JK_CACHE_DIR},
 *       {@code JK_STATE_DIR}, {@code JK_DATA_DIR}, {@code JK_BIN_DIR},
 *       {@code JK_JDKS_DIR}. Absolute paths; no jk suffix appended.</li>
 *   <li>Root: {@code JK_HOME} relocates the entire tree. Defaults to
 *       {@code $HOME/.jk}.</li>
 * </ul>
 *
 * <p>XDG Base Directory variables are deliberately not consulted —
 * jk owns its tree the way Cargo owns {@code ~/.cargo} and Rustup owns
 * {@code ~/.rustup}. Tool launchers under {@code ~/.jk/bin} should be
 * added to {@code $PATH} explicitly (jk's installer does this on first
 * run).
 *
 * <p>This class is configuration, not policy: it doesn't create
 * directories. Callers materialise paths with
 * {@link java.nio.file.Files#createDirectories}.
 */
public final class JkDirs {

    private static final String DEFAULT_HOME_SUFFIX = ".jk";

    private final Function<String, String> env;
    private final String userHome;

    private JkDirs(Function<String, String> env, String userHome) {
        this.env = Objects.requireNonNull(env, "env");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
    }

    /** Live resolver bound to {@link System#getenv} and {@code user.home}. */
    public static JkDirs current() {
        return new JkDirs(System::getenv, System.getProperty("user.home"));
    }

    /** Test seam: fully synthetic environment. */
    public static JkDirs of(Function<String, String> env, String userHome) {
        return new JkDirs(env, userHome);
    }

    /**
     * Back-compat seam for callers that still pass an OS string. The OS is
     * no longer consulted; the layout is identical on every platform.
     */
    public static JkDirs of(Function<String, String> env, String userHome, String ignoredOs) {
        return new JkDirs(env, userHome);
    }

    public static Path home()           { return current().homeDir(); }
    public static Path userConfigFile() { return current().userConfigFilePath(); }
    public static Path cache()          { return current().cacheDir(); }
    public static Path state()          { return current().stateDir(); }
    public static Path data()           { return current().dataDir(); }
    public static Path binDir()         { return current().binDirectory(); }
    public static Path jdks()           { return current().jdksDir(); }

    /**
     * The root of jk's on-disk tree. {@code JK_HOME} overrides; otherwise
     * {@code $HOME/.jk}.
     */
    public Path homeDir() {
        String override = nonBlank(env.apply("JK_HOME"));
        if (override != null) return Path.of(override);
        return Path.of(userHome).resolve(DEFAULT_HOME_SUFFIX);
    }

    /**
     * Single-file user config at {@code ~/.jk/config.toml}.
     * Overridable via {@code JK_CONFIG_FILE}.
     */
    public Path userConfigFilePath() {
        String override = nonBlank(env.apply("JK_CONFIG_FILE"));
        if (override != null) return Path.of(override);
        return homeDir().resolve("config.toml");
    }

    public Path cacheDir() {
        return resolve("JK_CACHE_DIR", "cache");
    }

    public Path stateDir() {
        return resolve("JK_STATE_DIR", "state");
    }

    public Path dataDir() {
        return resolve("JK_DATA_DIR", "data");
    }

    /**
     * Where {@code jk tool install} writes launchers. Defaults to
     * {@code ~/.jk/bin/} (cargo-style). Override via {@code JK_BIN_DIR}.
     * On a fresh install jk asks the user to add this directory to
     * {@code $PATH}.
     */
    public Path binDirectory() {
        return resolve("JK_BIN_DIR", "bin");
    }

    /**
     * Where {@code jk jdk install} extracts JDK tarballs. Defaults to
     * {@code ~/.jk/jdks/} on every platform. Override via
     * {@code JK_JDKS_DIR}. JDKs installed elsewhere (IntelliJ's
     * {@code ~/.jdks} or {@code ~/Library/Java/JavaVirtualMachines}, SDKMAN,
     * mise, system packages) are still discovered by the probe chain;
     * they're not stored here.
     */
    public Path jdksDir() {
        return resolve("JK_JDKS_DIR", "jdks");
    }

    /**
     * Per-method env override wins; otherwise {@code $JK_HOME/<segment>}.
     */
    private Path resolve(String jkEnv, String segment) {
        String override = nonBlank(env.apply(jkEnv));
        if (override != null) return Path.of(override);
        return homeDir().resolve(segment);
    }

    private static String nonBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
