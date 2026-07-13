// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.command;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.cache.VersionStore;
import dev.jkbuild.cli.CliOutput;
import dev.jkbuild.model.command.GroupCommand;
import dev.jkbuild.model.command.CliCommand;
import dev.jkbuild.model.command.Exit;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.util.Hashing;
import dev.jkbuild.util.JkDirs;
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
 * <p>{@code jk self update [--version <v>] [--force]}: resolve the target (default
 * {@code latest/VERSION}), download the platform client + engine jar from the frozen release
 * layout (releases.md), verify signature-then-hash ({@link dev.jkbuild.repo.ReleaseVerifier}),
 * materialize into {@code ~/.jk/versions/<v>/}, flip the {@code bin/jk} pointer, and start the
 * new engine — whose startup takes over the endpoint and gracefully drains the old daemon.
 * Nothing is killed; {@code --force} stops the old engine (and its jobs) first, for the
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
        return List.of(new UpdateSub());
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
            return List.of(
                    Opt.value("<x.y.z>", "Target version. Default: the latest release.", "--version"),
                    Opt.flag("Stop the running engine (and its jobs) immediately instead of draining.", "--force"));
        }

        @Override
        public int run(Invocation in) throws Exception {
            URI base = releasesBase();
            String target = in.value("version").orElse(null);
            dev.jkbuild.http.Http http = new dev.jkbuild.http.Http();
            if (target == null) {
                target = new String(get(http, URI.create(base + "/latest/VERSION"), "latest version pointer"),
                                StandardCharsets.UTF_8)
                        .trim();
            }
            if (target.isEmpty()) {
                CliOutput.err("jk self update: could not resolve a target version");
                return Exit.SOFTWARE;
            }
            VersionStore store = VersionStore.current();
            Cas cas = new Cas(JkDirs.cache());
            String running = dev.jkbuild.cli.Jk.VERSION;
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

            // Hand the engine over: --force stops the old daemon (killing its jobs) first;
            // otherwise the NEW engine's startup drains it gracefully — zero interrupted builds.
            var paths = dev.jkbuild.engine.EnginePaths.current();
            if (in.isSet("force")) {
                dev.jkbuild.cli.engine.EngineClient.forceStop(
                        dev.jkbuild.engine.EnginePaths.activeSocket(paths));
            }
            Path newClient = m.clientBin().orElse(null);
            if (newClient != null) {
                new ProcessBuilder(newClient.toString(), "engine", "start")
                        .inheritIO()
                        .start()
                        .waitFor();
                CliOutput.out("engine " + target + " is taking over"
                        + (in.isSet("force") ? "" : " (running builds finish on the old engine)"));
            }
            return 0;
        }

        private static VersionStore.Materialized fetchAndMaterialize(
                dev.jkbuild.http.Http http, URI base, String version, VersionStore store, Cas cas)
                throws IOException, InterruptedException {
            URI dir = URI.create(base + "/" + version + "/");
            byte[] sums = get(http, dir.resolve("SHA256SUMS"), "release checksums");
            var verifier = dev.jkbuild.repo.ReleaseVerifier.current(
                    dev.jkbuild.config.GlobalConfig.releaseTrustedKeys());
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
            cas.put(jar);
            String clientSha = Hashing.sha256Hex(client);
            cas.putFile(client, clientSha);
            return store.materialize(version, cas, jarSha, clientSha);
        }

        /** {@code jk-<os>-<arch>.zip} in HostPlatform's release vocabulary (releases.md). */
        private static String clientArtifactName(String version) {
            String os = dev.jkbuild.jdk.HostPlatform.currentOs().toLowerCase(java.util.Locale.ROOT);
            String arch = dev.jkbuild.jdk.HostPlatform.currentArch();
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
         * Flip {@code ~/.jk/bin/jk} to the materialized client — atomic symlink swap where the
         * platform allows, copy elsewhere. {@code jkx} follows.
         */
        private static void flipPointer(VersionStore.Materialized m) throws IOException {
            Path client = m.clientBin()
                    .orElseThrow(() -> new IOException("materialized " + m.version() + " has no client binary"));
            Path bin = JkDirs.binDir();
            Files.createDirectories(bin);
            Path jk = bin.resolve("jk");
            Path jkx = bin.resolve("jkx");
            try {
                Path tmp = bin.resolve(".jk-new");
                Files.deleteIfExists(tmp);
                Files.createSymbolicLink(tmp, client);
                Files.move(tmp, jk, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.deleteIfExists(jkx);
                Files.createSymbolicLink(jkx, client);
            } catch (IOException | UnsupportedOperationException e) {
                // No symlinks (some Windows setups): plain copies still upgrade correctly.
                Files.copy(client, jk, StandardCopyOption.REPLACE_EXISTING);
                Files.copy(client, jkx, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static byte[] get(dev.jkbuild.http.Http http, URI uri, String what)
                throws IOException, InterruptedException {
            HttpResponse<byte[]> response = http.get(uri);
            if (response.statusCode() != 200) {
                throw new IOException("could not download the " + what + " from " + uri + " — HTTP "
                        + response.statusCode());
            }
            return response.body();
        }

        private static URI releasesBase() {
            String override = System.getenv("JK_RELEASES_URL");
            return URI.create(override == null || override.isBlank()
                    ? "https://jkbuild.dev/releases"
                    : override);
        }
    }
}
