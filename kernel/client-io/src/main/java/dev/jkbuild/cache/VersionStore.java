// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cache;

import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Side-by-side materialized jk versions (engine-versioning-plan R1/R2): every immutable byte
 * lives in the {@link Cas}; a version usable by the OS lives under
 * {@code ~/.jk/versions/<v>/} — the client binary, the engine jar, and a {@code manifest.toml}
 * recording what was materialized. Three writers share this layout: {@code install.sh}, the
 * client's engine-jar self-fetch, and (later) {@code jk self update} / the project wrapper.
 *
 * <p>Materialization is idempotent and crash-safe: files are COPIED out of the CAS (never
 * linked — the CAS shares inodes with nothing), the version root is assembled under a temp
 * name, and {@code manifest.toml} is written by the atomic final rename — a version dir
 * without a manifest is an aborted materialization and is ignored (and re-done) by readers.
 */
public final class VersionStore {

    /** Marker completing a materialization; a version dir without it is ignored. */
    public static final String MANIFEST = "manifest.toml";

    private final Path root;

    public VersionStore(Path versionsDir) {
        this.root = versionsDir;
    }

    /** Rooted at the live {@code ~/.jk/versions} (honors {@code JK_HOME}). */
    public static VersionStore current() {
        return new VersionStore(JkDirs.versions());
    }

    /** One usable version on disk. {@code clientBin} is absent for engine-only materializations. */
    public record Materialized(String version, Path root, Optional<Path> clientBin, Path engineJar) {}

    public Path versionsDir() {
        return root;
    }

    /** The materialized version {@code v}, when complete on disk. */
    public Optional<Materialized> resolve(String v) {
        return read(versionsDir().resolve(v), v);
    }

    /**
     * The newest complete version on disk — the daemon the cold spawn should launch
     * (engine-versioning-plan R4). Ordering is dotted-numeric with {@code -SNAPSHOT} (or any
     * qualifier) sorting below its release.
     */
    public Optional<Materialized> newest() {
        Path dir = versionsDir();
        if (!Files.isDirectory(dir)) return Optional.empty();
        List<Materialized> found = new ArrayList<>();
        try (var entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (!Files.isDirectory(p)) continue;
                read(p, p.getFileName().toString()).ifPresent(found::add);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        return found.stream().max(Comparator.comparing(Materialized::version, VersionStore::compare));
    }

    /**
     * Materialize {@code version} from CAS blobs: copy the engine jar (and, when given, the
     * client binary) into a temp sibling, write the manifest, atomically rename into place.
     * Already-complete versions return immediately (immutable once materialized).
     *
     * @param clientBinSha CAS sha of this host's native client, or {@code null} (engine-only —
     *     the self-fetch path, where the running client IS this version's client)
     */
    public Materialized materialize(String version, Cas cas, String engineJarSha, String clientBinSha)
            throws IOException {
        Optional<Materialized> existing = resolve(version);
        if (existing.isPresent()) return existing.get();

        Path finalRoot = versionsDir().resolve(version);
        Files.createDirectories(versionsDir());
        Path tmp = Files.createTempDirectory(versionsDir(), "." + version + "-");
        try {
            Path lib = Files.createDirectories(tmp.resolve("lib"));
            Path engineJar = lib.resolve("jk-engine.jar");
            Files.copy(cas.pathFor(engineJarSha), engineJar, StandardCopyOption.REPLACE_EXISTING);
            String clientLine = "";
            if (clientBinSha != null) {
                Path bin = Files.createDirectories(tmp.resolve("bin"));
                Path client = bin.resolve("jk");
                Files.copy(cas.pathFor(clientBinSha), client, StandardCopyOption.REPLACE_EXISTING);
                makeExecutable(client);
                clientLine = "client-sha256 = \"" + clientBinSha + "\"\n";
            }
            Files.writeString(tmp.resolve(MANIFEST), "version = \"" + version + "\"\n"
                    + "engine-sha256 = \"" + engineJarSha + "\"\n"
                    + clientLine
                    + "protocol = 1\n");
            // An aborted earlier materialization (dir without manifest) blocks the rename —
            // clear it; a COMPLETE dir was returned above and never reaches this point.
            if (Files.isDirectory(finalRoot) && !Files.isRegularFile(finalRoot.resolve(MANIFEST))) {
                deleteRecursively(finalRoot);
            }
            try {
                Files.move(tmp, finalRoot, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Lost a race to a concurrent materializer — theirs is identical by content
                // addressing; use it.
                Optional<Materialized> won = resolve(version);
                if (won.isPresent()) return won.get();
                throw e;
            }
        } finally {
            deleteRecursively(tmp);
        }
        return resolve(version).orElseThrow(() -> new IOException("materialization of " + version + " left no manifest"));
    }

    /**
     * Materialize from loose files (install.sh parity for programmatic callers, and the
     * self-fetch path handing over the just-downloaded jar + the running client): the bytes are
     * ingested into the CAS first — the CAS stays the single source of truth.
     */
    public Materialized materializeFromFiles(String version, Cas cas, Path engineJar, Path clientBin)
            throws IOException {
        String engineSha = Hashing.sha256Hex(engineJar);
        cas.putFile(engineJar, engineSha);
        String clientSha = null;
        if (clientBin != null && Files.isRegularFile(clientBin)) {
            clientSha = Hashing.sha256Hex(clientBin);
            cas.putFile(clientBin, clientSha);
        }
        return materialize(version, cas, engineSha, clientSha);
    }

    // ---- internals -------------------------------------------------------

    private static Optional<Materialized> read(Path root, String version) {
        if (!Files.isRegularFile(root.resolve(MANIFEST))) return Optional.empty();
        Path engineJar = root.resolve("lib").resolve("jk-engine.jar");
        if (!Files.isRegularFile(engineJar)) return Optional.empty();
        Path client = root.resolve("bin").resolve("jk");
        return Optional.of(new Materialized(
                version, root, Files.isRegularFile(client) ? Optional.of(client) : Optional.empty(), engineJar));
    }

    /**
     * Version ordering: dotted numeric segments compare numerically; a qualifier
     * ({@code -SNAPSHOT}, {@code -rc1}, …) sorts BELOW its release; qualifiers compare
     * lexicographically among themselves. Intentionally small — release trains are simple
     * (releases.md), and this must never depend on the resolver.
     */
    static int compare(String a, String b) {
        String[] an = a.split("-", 2);
        String[] bn = b.split("-", 2);
        String[] as = an[0].split("\\.");
        String[] bs = bn[0].split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            long av = i < as.length ? parse(as[i]) : 0;
            long bv = i < bs.length ? parse(bs[i]) : 0;
            if (av != bv) return Long.compare(av, bv);
        }
        boolean aq = an.length > 1;
        boolean bq = bn.length > 1;
        if (aq != bq) return aq ? -1 : 1; // qualified < release
        if (!aq) return 0;
        return an[1].compareTo(bn[1]);
    }

    private static long parse(String seg) {
        try {
            return Long.parseLong(seg);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void makeExecutable(Path p) {
        try {
            var perms = Files.getPosixFilePermissions(p);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / restricted FS: executability is not permission-borne there.
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
