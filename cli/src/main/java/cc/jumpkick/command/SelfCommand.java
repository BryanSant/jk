// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.VersionStore;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.GroupCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.Hashing;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * {@code jk self} — the installation manages itself (engine-versioning-plan §3/§4).
 *
 * <p>{@code jk self update [<version>] [--now]}: resolve the target (default
 * {@code latest/VERSION}), download the platform client + engine jar from the frozen release
 * layout (releases.md), verify signature-then-hash ({@link cc.jumpkick.repo.ReleaseVerifier}),
 * materialize into {@code ~/.jk/versions/<v>/}, flip the {@code bin/jk} pointer, and start the
 * new engine — whose startup takes over the endpoint and gracefully drains the old daemon.
 * Nothing is killed; {@code --now} stops the old engine (and its jobs) first, for the
 * impatient and as the wedged-engine recovery hatch.
 */
public final class SelfCommand extends GroupCommand {

    @Override
    public String name() {
        return "self";
    }

    @Override
    public String description() {
        return "Manage this jk installation (update)";
    }

    @Override
    public List<CliCommand> subcommands() {
        return List.of(new UpdateSub(), new MaterializeSub());
    }

    /**
     * {@code jk self materialize <client-bin> <engine-jar>} — hidden install-time seam: ingest a
     * local dist's artifacts into the CAS and materialize {@code versions/<running>/} through the
     * ONE Java materializer. install.sh calls this through the freshly-installed client instead
     * of hand-rolling the layout in shell — a shell copy that skipped the CAS left pruned
     * versions unrecoverable (VersionStore.prune re-materializes from CAS blobs).
     */
    static final class MaterializeSub implements CliCommand {

        @Override
        public String name() {
            return "materialize";
        }

        @Override
        public String description() {
            return "Materialize versions/<running> from local artifacts (install-time seam)";
        }

        @Override
        public boolean hidden() {
            return true;
        }

        @Override
        public List<cc.jumpkick.model.command.Param> parameters() {
            return List.of(
                    cc.jumpkick.model.command.Param.of(
                            "client-bin", cc.jumpkick.model.command.Arity.ONE, "The jk client binary."),
                    cc.jumpkick.model.command.Param.of(
                            "engine-jar", cc.jumpkick.model.command.Arity.ONE, "The matching jk-engine jar."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            Path client = Path.of(in.positionals().get(0));
            Path engineJar = Path.of(in.positionals().get(1));
            if (!Files.isRegularFile(engineJar)) {
                CliOutput.err("jk self materialize: engine jar not found: " + engineJar);
                return Exit.SOFTWARE;
            }
            VersionStore.Materialized m = VersionStore.current()
                    .materializeFromFiles(cc.jumpkick.cli.Jk.VERSION, new Cas(JkDirs.cache()), engineJar, client);
            CliOutput.out("materialized " + m.root());
            return 0;
        }
    }

    static final class UpdateSub implements CliCommand {

        @Override
        public String name() {
            return "update";
        }

        @Override
        public String description() {
            return "Update jk to the latest (or a specific) release, taking over without killing builds";
        }

        @Override
        public List<Opt> options() {
            // No --version option: the global -V/--version flag owns that name (the dispatcher
            // rejects duplicates), so the target rides as a positional.
            return List.of(
                    Opt.flag("Stop the running engine (and its jobs) immediately instead of draining.", "--now"));
        }

        @Override
        public List<cc.jumpkick.model.command.Param> parameters() {
            return List.of(cc.jumpkick.model.command.Param.of(
                    "version",
                    cc.jumpkick.model.command.Arity.ZERO_OR_ONE,
                    "Target version x.y.z. Default: the latest release."));
        }

        @Override
        public int run(Invocation in) throws Exception {
            URI base = releasesBase();
            String target = in.positionals().isEmpty() ? null : in.positionals().get(0);
            cc.jumpkick.http.Http http = new cc.jumpkick.http.Http();
            if (target == null) {
                target = new String(
                                get(http, URI.create(base + "/latest/VERSION"), "latest version pointer"),
                                StandardCharsets.UTF_8)
                        .trim();
            }
            if (target.isEmpty()) {
                CliOutput.err("jk self update: could not resolve a target version");
                return Exit.SOFTWARE;
            }
            VersionStore store = VersionStore.current();
            Cas cas = new Cas(JkDirs.cache());
            String running = cc.jumpkick.cli.Jk.VERSION;
            if (target.equals(running) && store.resolve(target).isPresent()) {
                CliOutput.out("jk " + target + " is already current");
                return 0;
            }

            VersionStore.Materialized m = store.resolve(target).orElse(null);
            if (m == null) {
                m = fetchAndMaterialize(http, base, target, store, cas);
            }

            flipPointer(m);
            CliOutput.out("jk " + target + " installed (" + m.root() + ")");

            // Hand the engine over: --now stops the old daemon (killing its jobs) first;
            // otherwise the NEW engine's startup drains it gracefully — zero interrupted builds.
            var paths = cc.jumpkick.engine.EnginePaths.current();
            if (in.isSet("now")) {
                cc.jumpkick.cli.engine.EngineClient.forceStop(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
            }
            Path newClient = m.clientBin().orElse(null);
            if (newClient != null) {
                new ProcessBuilder(newClient.toString(), "engine", "start")
                        .inheritIO()
                        .start()
                        .waitFor();
                CliOutput.out("engine " + target + " is taking over"
                        + (in.isSet("now") ? "" : " (running builds finish on the old engine)"));
            }
            return 0;
        }

        static VersionStore.Materialized fetchAndMaterialize(
                cc.jumpkick.http.Http http, URI base, String version, VersionStore store, Cas cas)
                throws IOException, InterruptedException {
            URI dir = URI.create(base + "/" + version + "/");
            byte[] sums = get(http, dir.resolve("SHA256SUMS"), "release checksums");
            var verifier =
                    cc.jumpkick.repo.ReleaseVerifier.current(cc.jumpkick.config.GlobalConfig.releaseTrustedKeys());
            if (verifier.available()) {
                byte[] sig = get(http, dir.resolve("SHA256SUMS.sig"), "release signature");
                verifier.verify(sums, new String(sig, StandardCharsets.UTF_8));
            }

            // Engine jar (platform-neutral) + the platform client (.zip — the one archive format
            // the JDK opens natively; releases.md ships it on every platform).
            String jarName = "jk-engine-" + version + ".jar";
            byte[] jar = verified(get(http, dir.resolve(jarName), "engine jar"), sums, jarName);
            String clientName = clientArtifactName(version);
            byte[] clientZip = verified(get(http, dir.resolve(clientName), "client binary"), sums, clientName);
            Path client = unzipSingleBinary(clientZip);

            String jarSha = Hashing.sha256Hex(jar);
            cas.put(jar, jarSha);
            String clientSha = Hashing.sha256Hex(client);
            cas.putFile(client, clientSha);
            return store.materialize(version, cas, jarSha, clientSha);
        }

        /** {@code jk-<os>-<arch>.zip} in HostPlatform's release vocabulary (releases.md). */
        private static String clientArtifactName(String version) {
            String os = cc.jumpkick.jdk.HostPlatform.currentOs().toLowerCase(java.util.Locale.ROOT);
            String arch = cc.jumpkick.jdk.HostPlatform.currentArch();
            String suffix = "windows".equals(os) ? ".exe.zip" : ".zip";
            return "jk-" + os + "-" + arch + suffix;
        }

        private static Path unzipSingleBinary(byte[] zip) throws IOException {
            Path tmp = Files.createTempFile("jk-self-", ".bin");
            try (var zin = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                java.util.zip.ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    Files.copy(zin, tmp, StandardCopyOption.REPLACE_EXISTING);
                    return tmp;
                }
            }
            throw new IOException("release client archive contains no file");
        }

        private static byte[] verified(byte[] body, byte[] sums, String name) throws IOException {
            String expected = null;
            for (String line : new String(sums, StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].equals(name)) expected = parts[0];
            }
            if (expected == null) throw new IOException(name + " is not covered by the release's SHA256SUMS");
            String actual = Hashing.sha256Hex(body);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(name + " checksum mismatch — expected " + expected + ", got " + actual);
            }
            return body;
        }

        /**
         * Flip {@code ~/.jk/bin/jk} to the materialized client; {@code jkx} follows. Pointer
         * strategy is a ladder: symlink (POSIX; on Windows it needs Developer Mode /
         * SeCreateSymbolicLinkPrivilege) → hard link (NTFS allows it unprivileged, same-volume
         * only — bin/ and versions/ share ~/.jk — and it costs zero disk) → byte copy (last
         * resort: cross-volume {@code JK_INSTALL_DIR}, FAT-family filesystems). Every rung stages
         * at a temp sibling and renames into place, so readers never see a partial pointer.
         */
        private static void flipPointer(VersionStore.Materialized m) throws IOException {
            Path client = m.clientBin()
                    .orElseThrow(() -> new IOException("materialized " + m.version() + " has no client binary"));
            Path bin = JkDirs.binDir();
            Files.createDirectories(bin);
            repoint(bin.resolve("jk"), client);
            repoint(bin.resolve("jkx"), client);
        }

        private static void repoint(Path pointer, Path client) throws IOException {
            Path tmp = pointer.resolveSibling("." + pointer.getFileName() + "-new");
            Files.deleteIfExists(tmp);
            try {
                Files.createSymbolicLink(tmp, client);
            } catch (IOException | UnsupportedOperationException noSymlink) {
                try {
                    Files.createLink(tmp, client);
                } catch (IOException | UnsupportedOperationException noHardlink) {
                    Files.copy(client, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            try {
                Files.move(tmp, pointer, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException replaceDenied) {
                // Windows can rename a RUNNING exe but never delete/replace it (the image is
                // memory-mapped): step the live pointer aside, then slide the new one in. The
                // parked old image stays locked until that process exits — the next flip's
                // deleteIfExists reclaims it.
                Path old = pointer.resolveSibling("." + pointer.getFileName() + "-old");
                try {
                    Files.deleteIfExists(old);
                } catch (IOException stillRunning) {
                    // a previous update's parked image is still executing; park beside it
                    old = pointer.resolveSibling("." + pointer.getFileName() + "-old-" + System.nanoTime());
                }
                Files.move(pointer, old, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, pointer);
                try {
                    Files.deleteIfExists(old);
                } catch (IOException ignored) {
                    // locked while the old image runs; reclaimed on a later flip
                }
            }
        }

        private static byte[] get(cc.jumpkick.http.Http http, URI uri, String what)
                throws IOException, InterruptedException {
            HttpResponse<byte[]> response = http.get(uri);
            if (response.statusCode() != 200) {
                throw new IOException(
                        "could not download the " + what + " from " + uri + " — HTTP " + response.statusCode());
            }
            return response.body();
        }

        static URI releasesBase() {
            String override = System.getenv("JK_RELEASES_URL");
            return URI.create(override == null || override.isBlank() ? "https://jumpkick.build/releases" : override);
        }
    }
}
