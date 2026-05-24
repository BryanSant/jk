// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import dev.jkbuild.util.JkDirs;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The system-wide "default JDK" pointer plus the floating "current JDK"
 * pointer that {@code jk env} steers per project.
 *
 * <p>Two-channel storage so things work even on filesystems where symbolic
 * links aren't first-class:
 *
 * <ol>
 *   <li><b>Default symlink</b> at {@link JkDirs#data()}{@code /default-jdk}
 *       — written by {@link #set(InstalledJdk)} (i.e. {@code jk jdk install
 *       --make-default}). POSIX shells can source {@code JAVA_HOME}
 *       straight off this path.</li>
 *   <li><b>Current symlink</b> at {@link JkDirs#data()}{@code /current-jdk}
 *       — mirrors the default by default; {@link #setCurrent(InstalledJdk)}
 *       (called from {@code jk env}) re-points it at whatever JDK the
 *       current project pins.</li>
 *   <li><b>Config record</b>: {@code default-jdk = "<identifier>"} in
 *       {@link JkDirs#userConfigFile()}. Authoritative when the default
 *       symlink is missing or unreadable (Windows without dev mode,
 *       restricted filesystems, container layers).</li>
 * </ol>
 *
 * <p>Symlink writes are best-effort — {@link #currentIdentifier()} reads
 * the config record so the answer matches across platforms.
 */
public final class GlobalDefaultJdk {

    private static final Pattern DEFAULT_JDK_LINE =
            Pattern.compile("(?m)^default-jdk\\s*=\\s*.*$");

    private final Path defaultSymlink;
    private final Path currentSymlink;
    private final Path configFile;

    public GlobalDefaultJdk(Path defaultSymlink, Path currentSymlink, Path configFile) {
        this.defaultSymlink = defaultSymlink;
        this.currentSymlink = currentSymlink;
        this.configFile = configFile;
    }

    public static GlobalDefaultJdk current() {
        Path data = JkDirs.data();
        return new GlobalDefaultJdk(
                data.resolve("default-jdk"),
                data.resolve("current-jdk"),
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
     * Point the system default at {@code jdk}. Writes the config record
     * unconditionally; both symlinks (default + current) are best-effort.
     * Current is reset to match the new default — projects with their own
     * pin will re-flip it on the next {@code jk env}.
     */
    public void set(InstalledJdk jdk) throws IOException {
        writeConfigRecord(jdk.identifier());
        writeSymlink(defaultSymlink, jdk.home());
        writeSymlink(currentSymlink, jdk.home());
    }

    /**
     * Re-point the {@code current-jdk} symlink only — used by {@code jk env}
     * when a project pins a different JDK than the system default.
     */
    public void setCurrent(InstalledJdk jdk) throws IOException {
        writeSymlink(currentSymlink, jdk.home());
    }

    /**
     * Drop the system-wide default pointer entirely. Removes both symlinks
     * (best-effort) and strips the {@code default-jdk} line from the config
     * file. Other keys in the config file are preserved. Used by
     * {@code jk jdk uninstall} when the user removes the JDK that was the
     * default and no survivors remain (or by future {@code jk jdk default
     * --unset}).
     */
    public void clear() throws IOException {
        Files.deleteIfExists(defaultSymlink);
        Files.deleteIfExists(currentSymlink);
        if (!Files.exists(configFile)) return;
        String existing = Files.readString(configFile, StandardCharsets.UTF_8);
        Matcher m = DEFAULT_JDK_LINE.matcher(existing);
        if (!m.find()) return;
        String updated = m.replaceFirst("");
        // Collapse the blank line we may have left behind so consecutive
        // edits don't accumulate empty lines.
        updated = updated.replaceAll("(?m)^\\s*\\R", "");
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
    }

    /** Identifier stored under {@code default-jdk}, if any. */
    public Optional<String> currentIdentifier() throws IOException {
        if (!Files.exists(configFile)) return Optional.empty();
        TomlParseResult toml = Toml.parse(configFile);
        if (toml.hasErrors()) {
            // Surface the first error so callers can decide whether to ignore.
            throw new IOException("malformed " + configFile + ": "
                    + toml.errors().getFirst().getMessage());
        }
        String value = toml.getString("default-jdk");
        return Optional.ofNullable(value).filter(s -> !s.isBlank());
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

    private void writeConfigRecord(String identifier) throws IOException {
        Files.createDirectories(configFile.getParent());
        String line = "default-jdk = \"" + escape(identifier) + "\"";
        if (!Files.exists(configFile)) {
            Files.writeString(configFile, line + "\n", StandardCharsets.UTF_8);
            return;
        }
        String existing = Files.readString(configFile, StandardCharsets.UTF_8);
        Matcher m = DEFAULT_JDK_LINE.matcher(existing);
        String updated = m.find()
                ? m.replaceFirst(Matcher.quoteReplacement(line))
                : existing + (existing.endsWith("\n") ? "" : "\n") + line + "\n";
        Files.writeString(configFile, updated, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
