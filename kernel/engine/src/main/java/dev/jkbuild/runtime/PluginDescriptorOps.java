// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.model.PluginDeclaration;
import dev.jkbuild.plugin.manifest.PluginDescriptorStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The engine's write side of {@link PluginDescriptorStore}: extract each locked third-party plugin
 * jar's {@code jk-plugin.toml} out of the SHA-verified CAS into the module's manifest store, so
 * the parser (a plain-file reader) can validate the plugin's table on the next parse. Manifest
 * extraction is data, not code — it happens for untrusted plugins too; the trust gate sits in
 * front of worker forks ({@link PluginBuild#runWorker}).
 */
public final class PluginDescriptorOps {

    private PluginDescriptorOps() {}

    /** The manifest entry name at the root of a plugin jar. */
    public static final String MANIFEST_ENTRY = "jk-plugin.toml";

    /**
     * Materialize every locked declaration's manifest that is missing from {@code moduleDir}'s
     * store. CAS-only — never touches the network (sync/lock own fetching). Returns true when
     * anything new was written, so callers can re-parse.
     */
    public static boolean ensureMaterialized(Path moduleDir, Path cache) {
        Path lock = moduleDir.resolve("jk.lock");
        if (!Files.isRegularFile(lock)) return false;
        Lockfile lockfile;
        try {
            lockfile = LockfileReader.read(lock);
        } catch (Exception e) {
            return false;
        }
        boolean wrote = false;
        Cas cas = new Cas(cache);
        for (Lockfile.PluginEntry entry : lockfile.plugins()) {
            String sha = entry.sha256Hex();
            Path target = PluginDescriptorStore.fileFor(moduleDir, sha);
            if (Files.isRegularFile(target)) continue;
            Path jar = cas.pathFor(sha);
            if (!Files.isRegularFile(jar)) continue; // unsynced — jk sync fetches, then we extract
            try {
                materialize(moduleDir, sha, jar);
                wrote = true;
            } catch (IOException e) {
                // A jar without a root jk-plugin.toml is not a build plugin — leave it
                // unresolved; the unowned-table gate stays suppressed and the coordinate
                // is still usable as a plain locked artifact.
            }
        }
        return wrote;
    }

    /** Extract {@code jar}'s root manifest into the store (atomic move over a temp file). */
    public static void materialize(Path moduleDir, String sha256Hex, Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(MANIFEST_ENTRY);
            if (entry == null) {
                throw new IOException(jar + " has no root " + MANIFEST_ENTRY + " — not a build plugin");
            }
            String text;
            try (InputStream in = zip.getInputStream(entry)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Path target = PluginDescriptorStore.fileFor(moduleDir, sha256Hex);
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".manifest-", ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /** The locked + synced jar for {@code decl}, or empty (remediation: {@code jk sync}). */
    public static Optional<Path> jarFor(Path moduleDir, PluginDeclaration decl, Path cache) {
        return PluginDescriptorStore.lockEntry(moduleDir, decl)
                .map(e -> new Cas(cache).pathFor(e.sha256Hex()))
                .filter(Files::isRegularFile);
    }

    /** The declaration whose materialized manifest carries {@code pluginId}, or empty. */
    public static Optional<PluginDeclaration> declarationOf(Path moduleDir, JkBuild project, String pluginId) {
        for (PluginDeclaration decl : project.plugins()) {
            var manifest = PluginDescriptorStore.manifestFor(moduleDir, decl);
            if (manifest.isPresent() && manifest.get().id().equals(pluginId)) return Optional.of(decl);
        }
        return Optional.empty();
    }
}
