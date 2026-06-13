// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.run;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.plugin.host.HostEvent;
import dev.jkbuild.run.GoalListener;
import dev.jkbuild.worker.JvmOptions;
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
        // No CLI/toml context here — tune from the env layer + defaults.
        JvmOptions.Settings settings = JvmOptions.resolve(JvmOptions.Settings.NONE, null);
        List<String> cmd = hostCommand(javaExe, hostJar, specFile, settings);

        ReceivingGoalListener receiver = new ReceivingGoalListener(listeners);
        int[] reportedExit = {-1};

        int procExit = WorkerProcess.run(cmd, JvmOptions.toEnv(settings), HostEvent.PREFIX, json -> {
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

    /**
     * High-level entry point for build-family commands (build/test/native/image/…):
     * tries to fork the Host JVM and returns the exit code. Returns {@code -1}
     * when the host jar is not yet in the CAS (so the caller can fall back to
     * in-process execution without producing a user-visible error).
     *
     * <p>Attaches a {@link SimpleTaskListener} for spinner+result-line TUI and
     * the {@link EventLogListener} for the run log.
     *
     * @param inv    the build invocation (caller constructs; caller writes spec)
     * @param mode   rendering mode
     * @param spec   result-line spec (success/failure message)
     * @param verbose when true, logs the in-process fallback reason to stderr
     */
    public static int tryRun(dev.jkbuild.host.HostInvocation inv,
                              GoalConsole.Mode mode, ConsoleSpec spec,
                              boolean verbose)
            throws IOException, InterruptedException {
        return tryRun(inv, mode, spec, verbose, JvmOptions.Settings.NONE);
    }

    /**
     * As {@link #tryRun(dev.jkbuild.host.HostInvocation, GoalConsole.Mode, ConsoleSpec, boolean)},
     * with {@code cliJvm} carrying any {@code --max-ram-percent} / {@code --jvm-arg}
     * CLI overrides (highest precedence over env and {@code jk.toml}).
     */
    public static int tryRun(dev.jkbuild.host.HostInvocation inv,
                              GoalConsole.Mode mode, ConsoleSpec spec,
                              boolean verbose, JvmOptions.Settings cliJvm)
            throws IOException, InterruptedException {
        dev.jkbuild.cache.Cas cas = new dev.jkbuild.cache.Cas(inv.cache());
        try {
            WorkerJar.HOST.locate(cas);
        } catch (IllegalStateException e) {
            if (verbose) System.err.println("jk: host jar not in CAS, running in-process");
            return -1;
        }
        java.nio.file.Path specFile = dev.jkbuild.host.HostInvocation.write(inv);
        try {
            // Phase 4 / Phase 6 progress bar: start with SimpleTaskListener (spinner),
            // upgrade to ProgressBarListener once the Host sends the phases event.
            // Use an AtomicReference so the onPhasesReceived callback can swap it.
            var listeners = new java.util.ArrayList<GoalListener>();
            var consoleRef = new java.util.concurrent.atomic.AtomicReference<GoalListener>(
                    GoalConsole.makeListenerWithoutGoal(mode, spec));
            if (consoleRef.get() != null) listeners.add(consoleRef.get());
            try { listeners.add(EventLogListener.open(inv.cache(), inv.verb())); }
            catch (Exception ignored) {}

            // Build ReceivingGoalListener and wire the phases upgrade callback.
            var receiver = new ReceivingGoalListener(listeners);
            receiver.onPhasesReceived(names -> {
                GoalListener upgraded = GoalConsole.makeListenerWithPhases(mode, spec, names);
                GoalListener old = consoleRef.getAndSet(upgraded);
                // Replace old console listener in the list atomically.
                synchronized (listeners) {
                    int idx = listeners.indexOf(old);
                    if (idx >= 0) listeners.set(idx, upgraded);
                    else if (upgraded != null) listeners.add(0, upgraded);
                }
            });

            return runWithReceiver(specFile, cas, receiver,
                    JvmOptions.resolve(cliJvm, inv.dir()));
        } finally {
            java.nio.file.Files.deleteIfExists(specFile);
        }
    }

    /**
     * Run a workspace member through the Host, feeding its streamed events into a
     * shared {@link AggregateContext} (the calibrated workspace bar) via an
     * {@link AggregateMemberListener} — the Host-mode counterpart of
     * {@link GoalConsole#runGoalInto}. The member's own 0→100% is scaled into
     * {@code slice} (its reserved share of the aggregate total), and {@code phases}
     * — taken from the locally pre-scanned goal — drives the merged phase display,
     * since the forked Host has no in-process {@link dev.jkbuild.run.Goal} for the
     * CLI to read phase labels from.
     *
     * <p>Returns the Host's exit code, or {@code -1} when the host jar isn't in the
     * CAS, so the caller can fall back to in-process execution.
     */
    public static int tryRunInto(dev.jkbuild.host.HostInvocation inv,
                                 AggregateContext agg, String member,
                                 List<dev.jkbuild.run.Phase> phases, long slice,
                                 boolean verbose)
            throws IOException, InterruptedException {
        return tryRunInto(inv, agg, member, phases, slice, verbose, JvmOptions.Settings.NONE);
    }

    /** As {@link #tryRunInto(dev.jkbuild.host.HostInvocation, AggregateContext, String, List, long, boolean)}, with CLI JVM overrides. */
    public static int tryRunInto(dev.jkbuild.host.HostInvocation inv,
                                 AggregateContext agg, String member,
                                 List<dev.jkbuild.run.Phase> phases, long slice,
                                 boolean verbose, JvmOptions.Settings cliJvm)
            throws IOException, InterruptedException {
        Cas cas = new Cas(inv.cache());
        try {
            WorkerJar.HOST.locate(cas);
        } catch (IllegalStateException e) {
            if (verbose) System.err.println("jk: host jar not in CAS, running in-process");
            return -1;
        }
        Path specFile = dev.jkbuild.host.HostInvocation.write(inv);
        try {
            var listeners = new java.util.ArrayList<GoalListener>();
            listeners.add(new AggregateMemberListener(agg, member, phases, slice));
            try {
                GoalListener log = EventLogListener.open(inv.cache(), inv.verb());
                if (log != null) listeners.add(log);
            } catch (Exception ignored) {}
            return runWithReceiver(specFile, cas, new ReceivingGoalListener(listeners),
                    JvmOptions.resolve(cliJvm, inv.dir()));
        } finally {
            Files.deleteIfExists(specFile);
        }
    }

    /** Variant of {@link #run} that uses a pre-built {@link ReceivingGoalListener} and tuned {@code settings}. */
    private static int runWithReceiver(Path specFile, dev.jkbuild.cache.Cas cas,
                                        ReceivingGoalListener receiver, JvmOptions.Settings settings)
            throws IOException, InterruptedException {
        Path hostJar = WorkerJar.HOST.locate(cas);
        List<String> cmd = hostCommand(runningJavaExe(), hostJar, specFile, settings);

        int[] reportedExit = {-1};
        int procExit = WorkerProcess.run(cmd, JvmOptions.toEnv(settings), HostEvent.PREFIX, json -> {
            if (HostEvent.type(json) == HostEvent.Type.EXIT) {
                reportedExit[0] = HostEvent.exitCode(json);
            }
            receiver.accept(HostEvent.PREFIX + json);
        }, line -> System.err.println(line));

        return reportedExit[0] >= 0 ? reportedExit[0] : procExit;
    }

    /**
     * The host JVM command line: the JVM, its tuning flags (heap cap, GC, string
     * dedup — see {@link JvmOptions}), then {@code -jar jk-host.jar <spec>}.
     */
    private static List<String> hostCommand(Path javaExe, Path hostJar, Path specFile,
                                            JvmOptions.Settings settings) {
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaExe.toString());
        cmd.addAll(JvmOptions.flags(settings, 1));   // the host is a single resident JVM
        cmd.add("-jar");
        cmd.add(hostJar.toString());
        cmd.add(specFile.toAbsolutePath().toString());
        return cmd;
    }

    private static Path runningJavaExe() {
        String home = System.getProperty("java.home");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(home, "bin", win ? "java.exe" : "java");
    }
}
