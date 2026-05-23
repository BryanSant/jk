// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Resolves the on-disk directories jk uses for config, cache, state, the
 * user bin dir, and installed JDKs. Follows the XDG Base Directory
 * Specification on Linux and macOS:
 *
 * <pre>
 *   config()  =  $JK_CONFIG_DIR   |  $XDG_CONFIG_HOME/jk  |  ~/.config/jk
 *   cache()   =  $JK_CACHE_DIR    |  $XDG_CACHE_HOME/jk   |  ~/.cache/jk
 *   state()   =  $JK_STATE_DIR    |  $XDG_STATE_HOME/jk   |  ~/.local/state/jk
 *   binDir()  =  $JK_BIN_DIR      |  $XDG_BIN_HOME        |  ~/.local/bin
 *   jdks()    =  $JK_JDKS_DIR     |  (macOS) ~/Library/Java/JavaVirtualMachines  |  ~/.jdks
 * </pre>
 *
 * <p>{@code binDir()} is shared with other user-installed launchers
 * (uv/cargo style) — no {@code /jk} suffix.
 *
 * <p>The class is configuration, not policy: it doesn't create directories.
 * Callers materialise paths with {@link java.nio.file.Files#createDirectories}.
 */
public final class JkDirs {

    private final Function<String, String> env;
    private final String userHome;
    private final String os;

    private JkDirs(Function<String, String> env, String userHome, String os) {
        this.env = Objects.requireNonNull(env, "env");
        this.userHome = Objects.requireNonNull(userHome, "userHome");
        this.os = Objects.requireNonNull(os, "os");
    }

    /** Live resolver bound to {@link System#getenv} and {@code user.home}. */
    public static JkDirs current() {
        return new JkDirs(System::getenv,
                System.getProperty("user.home"),
                System.getProperty("os.name", ""));
    }

    /** Test seam: fully synthetic environment. */
    public static JkDirs of(Function<String, String> env, String userHome, String os) {
        return new JkDirs(env, userHome, os);
    }

    public static Path config()  { return current().configDir(); }
    public static Path cache()   { return current().cacheDir(); }
    public static Path state()   { return current().stateDir(); }
    public static Path binDir()  { return current().binDirectory(); }
    public static Path jdks()    { return current().jdksDir(); }

    public Path configDir() {
        return resolve("JK_CONFIG_DIR", "XDG_CONFIG_HOME", ".config");
    }

    public Path cacheDir() {
        return resolve("JK_CACHE_DIR", "XDG_CACHE_HOME", ".cache");
    }

    public Path stateDir() {
        return resolve("JK_STATE_DIR", "XDG_STATE_HOME", ".local/state");
    }

    /**
     * The directory user-installed launchers live in. Conventionally on
     * {@code $PATH}; shared with other tools' launchers, so no {@code /jk}
     * suffix. XDG_BIN_HOME is honored when set even though it's not part
     * of the formal spec.
     */
    public Path binDirectory() {
        String override = nonBlank(env.apply("JK_BIN_DIR"));
        if (override != null) return Path.of(override);
        String xdg = nonBlank(env.apply("XDG_BIN_HOME"));
        if (xdg != null) return Path.of(xdg);
        return home().resolve(".local").resolve("bin");
    }

    /**
     * Where jk installs JDKs. Defaults to the IntelliJ neighbor location
     * so jk and IntelliJ share downloads transparently:
     * {@code ~/Library/Java/JavaVirtualMachines/} on macOS,
     * {@code ~/.jdks/} on Linux/Windows. {@code JK_JDKS_DIR} overrides.
     */
    public Path jdksDir() {
        String override = nonBlank(env.apply("JK_JDKS_DIR"));
        if (override != null) return Path.of(override);
        if (isMacOs()) {
            return home().resolve("Library").resolve("Java").resolve("JavaVirtualMachines");
        }
        return home().resolve(".jdks");
    }

    private Path resolve(String jkEnv, String xdgEnv, String defaultSubpath) {
        String override = nonBlank(env.apply(jkEnv));
        if (override != null) return Path.of(override);
        String xdg = nonBlank(env.apply(xdgEnv));
        if (xdg != null) return Path.of(xdg).resolve("jk");
        Path base = home();
        for (String segment : defaultSubpath.split("/")) {
            base = base.resolve(segment);
        }
        return base.resolve("jk");
    }

    private Path home() {
        return Path.of(userHome);
    }

    private boolean isMacOs() {
        String lower = os.toLowerCase(Locale.ROOT);
        return lower.contains("mac") || lower.contains("darwin");
    }

    private static String nonBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
