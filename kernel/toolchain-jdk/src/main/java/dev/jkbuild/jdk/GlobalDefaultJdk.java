// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.config.TomlScan;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The system-wide "default JDK" pointer plus the floating "current JDK" pointer that {@code jk env}
 * steers per project.
 *
 * <p>Two-channel storage so things work even on filesystems where symbolic links aren't
 * first-class:
 *
 * <ol>
 *   <li><b>Default symlink</b> at {@link JkDirs#data()}{@code /default-jdk} — written by {@link
 *       #set(InstalledJdk)} (i.e. {@code jk jdk install --make-default}). POSIX shells can source
 *       {@code JAVA_HOME} straight off this path.
 *   <li><b>Current symlink</b> at {@link JkDirs#data()}{@code /current-jdk} — mirrors the default
 *       by default; {@link #setCurrent(InstalledJdk)} (called from {@code jk env}) re-points it at
 *       whatever JDK the current project pins.
 *   <li><b>Config record</b>: {@code default-jdk = "<identifier>"} in {@link
 *       JkDirs#userConfigFile()}. Authoritative when the default symlink is missing or unreadable
 *       (Windows without dev mode, restricted filesystems, container layers).
 * </ol>
 *
 * <p>Symlink writes are best-effort — {@link #currentIdentifier()} reads the config record so the
 * answer matches across platforms.
 */
public final class GlobalDefaultJdk {

    private static final String DEFAULT_KEY = "default-jdk";
    private static final String GRAAL_KEY = "default-graal-jdk";
    // The home path uniquely identifies WHICH install is the default, so two
    // installs that share a vendor-major identifier (e.g. one under ~/.jk/jdks
    // and one under ~/.jdks) don't both look like the default.
    private static final String DEFAULT_HOME_KEY = "default-jdk-home";
    private static final String GRAAL_HOME_KEY = "default-graal-jdk-home";

    /** Matches a {@code <key> = ...} line so we can replace/strip it in place. */
    private static Pattern linePattern(String key) {
        return Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*=\\s*.*$");
    }

    private final Path defaultSymlink;
    private final Path currentSymlink;
    private final Path defaultGraalSymlink;
    private final Path configFile;

    public GlobalDefaultJdk(Path defaultSymlink, Path currentSymlink, Path defaultGraalSymlink, Path configFile) {
        this.defaultSymlink = defaultSymlink;
        this.currentSymlink = currentSymlink;
        this.defaultGraalSymlink = defaultGraalSymlink;
        this.configFile = configFile;
    }

    /** Back-compat: derive the default-graal symlink as a sibling of the default. */
    public GlobalDefaultJdk(Path defaultSymlink, Path currentSymlink, Path configFile) {
        this(defaultSymlink, currentSymlink, defaultSymlink.resolveSibling("default-graal-jdk"), configFile);
    }

    public static GlobalDefaultJdk current() {
        Path data = JkDirs.data();
        return new GlobalDefaultJdk(
                data.resolve("default-jdk"),
                data.resolve("current-jdk"),
                data.resolve("default-graal-jdk"),
                JkDirs.userConfigFile());
    }

    public Path symlink() {
        return defaultSymlink;
    }

    public Path currentSymlink() {
        return currentSymlink;
    }

    public Path configFile() {
        return configFile;
    }

    /**
     * Point the system default at {@code jdk}. Writes the config record unconditionally; both
     * symlinks (default + current) are best-effort. Current is reset to match the new default —
     * projects with their own pin will re-flip it on the next {@code jk env}.
     */
    public void set(InstalledJdk jdk) throws IOException {
        writeConfigRecord(DEFAULT_KEY, jdk.identifier());
        writeConfigRecord(DEFAULT_HOME_KEY, jdk.home().toString());
        writeSymlink(defaultSymlink, jdk.home());
        writeSymlink(currentSymlink, jdk.home());
    }

    /**
     * Re-point the {@code current-jdk} symlink only — used by {@code jk env} when a project pins a
     * different JDK than the system default.
     */
    public void setCurrent(InstalledJdk jdk) throws IOException {
        writeSymlink(currentSymlink, jdk.home());
    }

    /**
     * Point the default <em>GraalVM</em> at {@code jdk} (set by {@code jk jdk graal}). Independent of
     * the default/current java JDK: it backs {@code GRAALVM_HOME} and {@code jk native}. Writes the
     * {@code default-graal-jdk} config record + a best-effort symlink.
     */
    public void setGraal(InstalledJdk jdk) throws IOException {
        writeConfigRecord(GRAAL_KEY, jdk.identifier());
        writeConfigRecord(GRAAL_HOME_KEY, jdk.home().toString());
        writeSymlink(defaultGraalSymlink, jdk.home());
    }

    public Path graalSymlink() {
        return defaultGraalSymlink;
    }

    /** Identifier stored under {@code default-graal-jdk}, if any. */
    public Optional<String> graalIdentifier() throws IOException {
        return readKey(GRAAL_KEY);
    }

    /**
     * The exact home of the default <em>java</em> JDK — the {@code default-jdk-home} config record
     * (authoritative, cross-platform), else the {@code default-jdk} symlink's resolved path. This
     * pins which install is the default even when two installs share a vendor-major identifier.
     */
    public Optional<Path> defaultHome() {
        return resolveHome(DEFAULT_HOME_KEY, defaultSymlink);
    }

    /** The exact home of the default GraalVM (see {@link #defaultHome()}). */
    public Optional<Path> graalHome() {
        return resolveHome(GRAAL_HOME_KEY, defaultGraalSymlink);
    }

    private Optional<Path> resolveHome(String configKey, Path symlink) {
        // Config record is authoritative; readKey degrades to empty on a bad file.
        Optional<String> recorded = readKey(configKey);
        if (recorded.isPresent()) {
            Path home = Path.of(recorded.get());
            if (Files.isDirectory(home)) return Optional.of(home);
        }
        try {
            if (Files.exists(symlink)) return Optional.of(symlink.toRealPath());
        } catch (IOException ignored) {
            // broken/unsupported symlink — give up
        }
        return Optional.empty();
    }

    /** Drop the default-graal pointer (symlink + config line); other keys kept. */
    public void clearGraal() throws IOException {
        Files.deleteIfExists(defaultGraalSymlink);
        stripKey(GRAAL_KEY);
        stripKey(GRAAL_HOME_KEY);
    }

    /**
     * Drop the system-wide default pointer entirely. Removes both symlinks (best-effort) and strips
     * the {@code default-jdk} line from the config file. Other keys in the config file are preserved.
     * Used by {@code jk jdk uninstall} when the user removes the JDK that was the default and no
     * survivors remain (or by future {@code jk jdk default --unset}).
     */
    public void clear() throws IOException {
        Files.deleteIfExists(defaultSymlink);
        Files.deleteIfExists(currentSymlink);
        stripKey(DEFAULT_KEY);
        stripKey(DEFAULT_HOME_KEY);
    }

    /** Remove a single {@code <key> = ...} line from the config, preserving others. */
    private void stripKey(String key) throws IOException {
        if (!Files.exists(configFile)) return;
        String existing = Files.readString(configFile, StandardCharsets.UTF_8);
        Matcher m = linePattern(key).matcher(existing);
        if (!m.find()) return;
        String updated = m.replaceFirst("");
        // Collapse the blank line we may have left behind so consecutive
        // edits don't accumulate empty lines.
        updated = updated.replaceAll("(?m)^\\s*\\R", "");
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
    }

    /**
     * The {@code current-jdk} pointer's resolved home, if the symlink exists and points at a live
     * directory. Empty when unset, broken, or unsupported by the filesystem — callers then fall back
     * to the configured default.
     */
    public Optional<Path> currentHome() {
        try {
            if (!Files.exists(currentSymlink)) return Optional.empty();
            return Optional.of(currentSymlink.toRealPath());
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    /** Identifier stored under {@code default-jdk}, if any. */
    public Optional<String> currentIdentifier() throws IOException {
        return readKey(DEFAULT_KEY);
    }

    /**
     * Read a top-level string config key, or empty when absent/blank. Degrades to empty on a missing
     * or malformed config (consistent with every other jk config reader — a stray syntax error must
     * not break {@code jk jdk}); the symlink channel remains as a fallback signal. Reads via
     * {@link TomlScan} — this key sits on the shell hook's fallback path (thin-client C: no TOML
     * parser client-side).
     */
    private Optional<String> readKey(String key) {
        return Optional.ofNullable(TomlScan.scan(configFile, key).get(key)).filter(v -> !v.isBlank());
    }

    private void writeSymlink(Path symlink, Path target) throws IOException {
        Files.createDirectories(symlink.getParent());
        Files.deleteIfExists(symlink);
        try {
            Files.createSymbolicLink(symlink, target);
        } catch (UnsupportedOperationException | FileSystemException ignored) {
            // Symlinks unsupported (Windows w/o dev mode, fs without link
            // support). The config record is the authoritative channel.
        }
    }

    private void writeConfigRecord(String key, String identifier) throws IOException {
        Files.createDirectories(configFile.getParent());
        String line = key + " = \"" + escape(identifier) + "\"";
        if (!Files.exists(configFile)) {
            Files.writeString(configFile, line + "\n", StandardCharsets.UTF_8);
            return;
        }
        String existing = Files.readString(configFile, StandardCharsets.UTF_8);
        Matcher m = linePattern(key).matcher(existing);
        String updated = m.find()
                ? m.replaceFirst(Matcher.quoteReplacement(line))
                : existing + (existing.endsWith("\n") ? "" : "\n") + line + "\n";
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
