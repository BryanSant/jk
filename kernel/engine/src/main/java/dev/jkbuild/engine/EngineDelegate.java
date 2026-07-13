// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.engine;

import dev.jkbuild.cache.VersionStore;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Downward delegation (engine-versioning-plan §3 / R6): a build whose {@code jk.lock} pins an
 * OLDER jk than this daemon runs as a child of that exact version — {@code jk-engine --job},
 * one request over stdio, exit when done — the worker pattern. The child resolves its own
 * worker set (its {@code versions/<v>/manifest.toml} world), and its event stream is relayed
 * to the client verbatim: byte-identical to running that version's daemon directly.
 *
 * <p>Upward is never delegation: a pin NEWER than the daemon triggers takeover client-side
 * (the daemon must not supervise wire semantics it postdates).
 */
public final class EngineDelegate {

    private EngineDelegate() {}

    /**
     * The project's pinned jk version when it differs from {@code running}, else {@code null}
     * (no lock, no pin, or same version — serve locally).
     */
    public static String pinnedVersionDiffering(Path entryDir, String running) {
        try {
            Path lock = entryDir.resolve("jk.lock");
            if (!Files.isRegularFile(lock)) return null;
            Lockfile lf = LockfileReader.read(lock);
            if (lf.jk() == null || lf.jk().version() == null) return null;
            String pin = lf.jk().version();
            return pin.equals(running) ? null : pin;
        } catch (IOException | RuntimeException e) {
            return null; // unreadable lock → the normal request path surfaces the real error
        }
    }

    /** True when {@code pin} is newer than {@code running} (see VersionStore ordering). */
    public static boolean pinIsNewer(String pin, String running) {
        return dev.jkbuild.cache.VersionStore.compare(pin, running) > 0;
    }

    /**
     * Run {@code requestLine} on the pinned version's engine as a one-shot child, relaying its
     * event stream to {@code clientOut} and pumping {@code clientIn} (cancel messages) to the
     * child. Returns the child's exit code; throws when the version isn't materialized.
     */
    public static int runAsChild(
            String version, String requestLine, BufferedReader clientIn, BufferedWriter clientOut, Consumer<String> log)
            throws IOException, InterruptedException {
        VersionStore.Materialized m = VersionStore.current()
                .resolve(version)
                .orElseThrow(() -> new IOException("this build pins jk " + version
                        + ", which is not materialized on this machine — run its wrapper (./jk) once, or"
                        + " `jk self update`, to fetch it"));
        Path java = dev.jkbuild.jdk.JavaHomes.runningJavaHome().resolve("bin").resolve("java");
        List<String> cmd = List.of(
                java.toString(),
                "-cp",
                m.engineJar().toString(),
                "dev.jkbuild.cli.EngineMain",
                "--job");
        log.accept("jk engine: delegating to pinned jk " + version + " (job child)");
        Process child = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        try (BufferedWriter childIn = new BufferedWriter(
                        new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader childOut = new BufferedReader(
                        new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            childIn.write(requestLine);
            childIn.write('\n');
            childIn.flush();

            // Client → child pump (build-cancel etc.); daemon thread — dies with the exchange.
            Thread pump = new Thread(() -> {
                try {
                    String line;
                    while ((line = clientIn.readLine()) != null) {
                        childIn.write(line);
                        childIn.write('\n');
                        childIn.flush();
                    }
                } catch (IOException ignored) {
                    // client hung up — the child notices EOF on its own
                }
            }, "jk-engine-delegate-pump");
            pump.setDaemon(true);
            pump.start();

            // Child → client relay, verbatim.
            String line;
            while ((line = childOut.readLine()) != null) {
                clientOut.write(line);
                clientOut.write('\n');
                clientOut.flush();
            }
            return child.waitFor();
        } finally {
            if (child.isAlive()) child.destroy();
        }
    }
}
