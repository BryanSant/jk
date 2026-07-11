// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Content-addressed exploded views of archive artifacts whose packaging is a container, not a
 * classpath entry — an Android AAR materializes as {@code classes.jar} (classpath), {@code res/}
 * (resource link inputs), {@code AndroidManifest.xml} (manifest merge), {@code R.txt},
 * {@code proguard.txt} (consumer rules). The explosion is keyed by the archive's SHA-256 —
 * exactly the CAS discipline — so it happens once per artifact ever, survives across projects,
 * and can never serve stale content (new bytes = new key).
 *
 * <p>This is resolver-owned artifact-type materialization (android-plan §2 gap table): plugins
 * never learn the CAS; they receive already-exploded directories as runtime entries.
 */
public final class ExplodedArchives {

    private ExplodedArchives() {}

    /** The exploded dir for the CAS blob {@code sha256Hex}, exploding on first use. */
    public static Path explode(Cas cas, String sha256Hex) throws IOException {
        Path archive = cas.pathFor(sha256Hex);
        if (!Files.isRegularFile(archive)) {
            throw new IOException("archive blob missing from the cache: " + sha256Hex + " — run `jk sync`");
        }
        return explodeAt(cas.root().resolve("exploded").resolve(shard(sha256Hex)), archive);
    }

    /** The exploded dir for an arbitrary archive file (a workspace sibling's AAR), keyed by its content. */
    public static Path explodeFile(Cas cas, Path archive) throws IOException {
        String hex = dev.jkbuild.util.Hashing.sha256Hex(archive);
        return explodeAt(cas.root().resolve("exploded").resolve(shard(hex)), archive);
    }

    private static String shard(String hex) {
        return hex.substring(0, 2) + "/" + hex;
    }

    private static Path explodeAt(Path dir, Path archive) throws IOException {
        if (Files.isDirectory(dir)) return dir; // content-addressed: an existing dir is authoritative
        Path staging = Files.createTempDirectory(Files.createDirectories(dir.getParent()), ".exploding-");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path out = staging.resolve(entry.getName()).normalize();
                if (!out.startsWith(staging)) {
                    throw new IOException("archive entry escapes its root (zip-slip): " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        try {
            Files.move(staging, dir, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (!Files.isDirectory(dir)) throw e; // lost a race — the winner's dir serves
            dev.jkbuild.util.PathUtil.deleteRecursively(staging);
        }
        return dir;
    }
}
