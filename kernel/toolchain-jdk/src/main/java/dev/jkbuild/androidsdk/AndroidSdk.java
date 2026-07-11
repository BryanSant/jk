// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.androidsdk;

import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * The Android SDK root jk manages (android-plan.md §3.2 — "extend, don't invent"): reuse an
 * existing Studio/CI SDK when one is discoverable, otherwise a jk-owned directory the installer
 * populates component by component.
 *
 * <p>Root resolution, first hit wins:
 *
 * <ol>
 *   <li>{@code $ANDROID_HOME}, then {@code $ANDROID_SDK_ROOT} — the ecosystem's contracts.
 *   <li>{@code ~/Android/Sdk} (Linux/Windows) / {@code ~/Library/Android/sdk} (macOS) — Studio's
 *       defaults, so Studio users never download twice.
 *   <li>{@code ~/.jk/android-sdk} — the jk-managed root (created on demand).
 * </ol>
 *
 * <p>When a foreign root is discovered, {@code ~/.jk/android-sdk} becomes a symlink to it (the
 * JDK registry's link-discovered pattern), so every later path in jk is stable regardless of
 * where the SDK really lives — and so an already-accepted {@code licenses/} directory (the
 * sdkmanager on-disk contract this class shares) carries over.
 */
public final class AndroidSdk {

    private final Path root;

    private AndroidSdk(Path root) {
        this.root = root;
    }

    /** Test/CI seam: overrides the SDK root outright (no discovery, no symlink). */
    public static final String ROOT_PROPERTY = "jk.android.sdkRoot";

    public static AndroidSdk resolve() throws IOException {
        String override = System.getProperty(ROOT_PROPERTY);
        if (override != null && !override.isBlank()) {
            return new AndroidSdk(Files.createDirectories(Path.of(override)));
        }
        return resolve(System::getenv, JkDirs.home().resolve("android-sdk"));
    }

    /** Explicit-env seam for tests. */
    static AndroidSdk resolve(Function<String, String> env, Path managedRoot) throws IOException {
        if (Files.isDirectory(managedRoot) || Files.isSymbolicLink(managedRoot)) {
            return new AndroidSdk(managedRoot);
        }
        Path discovered = discover(env);
        if (discovered != null) {
            Files.createDirectories(managedRoot.getParent());
            try {
                Files.createSymbolicLink(managedRoot, discovered);
            } catch (IOException | UnsupportedOperationException e) {
                // No symlinks (odd FS) — use the discovered root directly.
                return new AndroidSdk(discovered);
            }
            return new AndroidSdk(managedRoot);
        }
        Files.createDirectories(managedRoot);
        return new AndroidSdk(managedRoot);
    }

    private static Path discover(Function<String, String> env) {
        for (String var : new String[] {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String value = env.apply(var);
            if (value != null && !value.isBlank() && Files.isDirectory(Path.of(value))) {
                return Path.of(value).toAbsolutePath();
            }
        }
        String home = System.getProperty("user.home", "");
        for (Path studio : new Path[] {
            Path.of(home, "Android", "Sdk"), Path.of(home, "Library", "Android", "sdk")
        }) {
            // Only adopt a Studio root that really is an SDK (licenses/ or platforms/ present).
            if (Files.isDirectory(studio.resolve("platforms")) || Files.isDirectory(studio.resolve("licenses"))) {
                return studio.toAbsolutePath();
            }
        }
        return null;
    }

    public Path root() {
        return root;
    }

    /** {@code platforms;android-28} → {@code <root>/platforms/android-28} — sdkmanager's layout. */
    /**
     * The installed component's dotted revision, read from its {@code source.properties}
     * ({@code Pkg.Revision} — the sdkmanager on-disk contract), or null when absent/unreadable.
     */
    public String installedRevision(String componentPath) {
        java.nio.file.Path props = componentDir(componentPath).resolve("source.properties");
        if (!java.nio.file.Files.isRegularFile(props)) return null;
        try {
            for (String line : java.nio.file.Files.readAllLines(props)) {
                if (line.startsWith("Pkg.Revision=")) return line.substring("Pkg.Revision=".length()).strip();
            }
        } catch (java.io.IOException ignored) {
            // unreadable — treated as unknown
        }
        return null;
    }

    public Path componentDir(String componentPath) {
        Path dir = root;
        for (String part : componentPath.split(";")) {
            dir = dir.resolve(part);
        }
        return dir;
    }

    /** A component is installed when its directory exists non-empty. */
    public boolean installed(String componentPath) {
        Path dir = componentDir(componentPath);
        try (var listing = Files.isDirectory(dir) ? Files.list(dir) : null) {
            return listing != null && listing.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * True when {@code licenseId} has {@code textHash} recorded under {@code <root>/licenses/} —
     * the exact sdkmanager contract (one accepted SHA-1 per line), so acceptance interops with
     * Studio in both directions.
     */
    public boolean licenseAccepted(String licenseId, String textHash) {
        Path file = root.resolve("licenses").resolve(licenseId);
        if (!Files.isRegularFile(file)) return false;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.strip().equalsIgnoreCase(textHash)) return true;
            }
        } catch (IOException ignored) {
            // unreadable → not accepted
        }
        return false;
    }

    /** Record acceptance of {@code licenseId} (appends, preserving other accepted hashes). */
    public void recordLicense(String licenseId, String textHash) throws IOException {
        Path dir = Files.createDirectories(root.resolve("licenses"));
        Path file = dir.resolve(licenseId);
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.strip().equalsIgnoreCase(textHash)) return;
            }
            Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8).stripTrailing() + "\n" + textHash
                    + "\n");
            return;
        }
        Files.writeString(file, textHash + "\n");
    }
}
