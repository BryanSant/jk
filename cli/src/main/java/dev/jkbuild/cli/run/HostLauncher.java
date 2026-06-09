// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.plugin.host.HostEvent;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.worker.WorkerJar;
import dev.jkbuild.worker.WorkerProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Forks the Workspace Host JVM and bridges its event stream back to the CLI's
 * {@link GoalListener}s. Replacing the in-process {@link dev.jkbuild.cli.run.GoalConsole}
 * call for build-family commands.
 *
 * <p>The protocol:
 * <ol>
 *   <li>The CLI writes a {@link dev.jkbuild.host.HostInvocation} spec file.</li>
 *   <li>Forks {@code java -jar jk-host.jar <specFile>} using the project-pinned
 *       JDK (or the running JDK as fallback).</li>
 *   <li>Reads stdout line by line: {@link HostEvent#PREFIX}-prefixed lines are
 *       dispatched through a {@link ReceivingGoalListener}; other lines (JVM
 *       noise, passthrough stderr) are forwarded to the user's stderr.</li>
 *   <li>When the Host emits {@code exit} the process has already exited — the
 *       exit code is returned to the caller.</li>
 * </ol>
 */
public final class HostLauncher {

    private HostLauncher() {}

    /**
     * Run a host invocation spec-file, routing events to {@code listeners}
     * and returning the exit code the Host reported (or the process exit code
     * if it terminated without an {@code exit} event).
     *
     * @param specFile  written by the caller via {@link dev.jkbuild.host.HostInvocation#write}
     * @param javaExe   path to the JVM to use (use the running JVM as fallback)
     * @param hostJar   path to the {@code jk-host} fat jar
     * @param listeners the listeners to receive goal events (TUI, run-log, …)
     */
    public static int run(Path specFile, Path javaExe, Path hostJar, List<GoalListener> listeners)
            throws IOException, InterruptedException {
        List<String> cmd = List.of(
                javaExe.toString(),
                "-jar", hostJar.toString(),
                specFile.toAbsolutePath().toString());

        ReceivingGoalListener receiver = new ReceivingGoalListener(listeners);
        int[] reportedExit = {-1};

        int procExit = WorkerProcess.run(cmd, HostEvent.PREFIX, json -> {
            if (HostEvent.type(json) == HostEvent.Type.EXIT) {
                reportedExit[0] = HostEvent.exitCode(json);
            }
            // reconstruct the full line so receiver can parse from raw json
            receiver.accept(HostEvent.PREFIX + json);
        }, line -> {
            // Passthrough: JVM startup noise, println from inside the build.
            // Forward to stderr so the TUI isn't polluted.
            System.err.println(line);
        });

        return reportedExit[0] >= 0 ? reportedExit[0] : procExit;
    }

    /**
     * Convenience overload: resolves the host JAR from the {@link WorkerJar}
     * registry and uses the running JVM.
     */
    public static int run(Path specFile, Cas cas, List<GoalListener> listeners)
            throws IOException, InterruptedException {
        Path hostJar = WorkerJar.HOST.locate(cas);
        Path javaExe = runningJavaExe();
        return run(specFile, javaExe, hostJar, listeners);
    }

    private static Path runningJavaExe() {
        String home = System.getProperty("java.home");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(home, "bin", win ? "java.exe" : "java");
    }
}
