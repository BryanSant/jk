// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.plugin.manifest;

import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.PluginDeclaration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The materialized-manifest store for third-party plugins (build-plugins plan §3.4 / P5): the
 * engine extracts each locked plugin jar's {@code jk-plugin.toml} into
 * {@code <module>/target/plugin-manifests/<sha256>.jk-plugin.toml} (content-addressed by the
 * jar's lock-pinned SHA), and this class is the read side — plain file reads, so the parser can
 * consult it without reaching the CAS machinery. A missing store file simply reads as an
 * unresolved declaration; the engine's flows materialize (sync/lock/build pre-flight) and
 * re-parse.
 *
 * <p>The engine never classloads third-party code to get here: a manifest is data, extracted
 * from a SHA-verified jar and safe to evaluate even for untrusted plugins (the trust gate sits
 * in front of worker <em>forks</em>, not manifest reads).
 */
public final class PluginDescriptorStore {

    private PluginDescriptorStore() {}

    /** Parsed manifests memoized by the jar SHA the store file is named for. */
    private static final Map<String, PluginDescriptor> BY_SHA = new ConcurrentHashMap<>();

    public static Path storeDir(Path moduleDir) {
        return moduleDir.resolve("target").resolve("plugin-manifests");
    }

    public static Path fileFor(Path moduleDir, String sha256Hex) {
        return storeDir(moduleDir).resolve(sha256Hex + ".jk-plugin.toml");
    }

    /** The lock's pinned entry for {@code decl}, or empty when unlocked/no lock. */
    public static Optional<Lockfile.PluginEntry> lockEntry(Path moduleDir, PluginDeclaration decl) {
        Path lock = moduleDir.resolve("jk.lock");
        if (!Files.isRegularFile(lock)) return Optional.empty();
        try {
            for (Lockfile.PluginEntry e : LockfileReader.read(lock).plugins()) {
                if (e.coordinate().equals(decl.coordinate())
                        && e.version().equals(decl.version())) {
                    return Optional.of(e);
                }
            }
        } catch (Exception ignored) {
            // unreadable lock — reads as unlocked, the same soft behavior every reader has
        }
        return Optional.empty();
    }

    /** The materialized manifest for {@code decl}, or empty when not yet locked + extracted. */
    public static Optional<PluginDescriptor> manifestFor(Path moduleDir, PluginDeclaration decl) {
        if (moduleDir == null) return Optional.empty();
        Optional<Lockfile.PluginEntry> entry = lockEntry(moduleDir, decl);
        if (entry.isEmpty()) return Optional.empty();
        String sha = entry.get().sha256Hex();
        PluginDescriptor memo = BY_SHA.get(sha);
        if (memo != null) return Optional.of(memo);
        Path file = fileFor(moduleDir, sha);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            PluginDescriptor parsed = PluginDescriptors.parse(
                    Files.readString(file, StandardCharsets.UTF_8),
                    decl.coordinateWithVersion() + "!jk-plugin.toml");
            BY_SHA.put(sha, parsed);
            return Optional.of(parsed);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** True when any declaration lacks a materialized manifest (validation must stay soft). */
    public static boolean hasUnresolved(Path moduleDir, List<PluginDeclaration> decls) {
        for (PluginDeclaration decl : decls) {
            if (manifestFor(moduleDir, decl).isEmpty()) return true;
        }
        return false;
    }
}
