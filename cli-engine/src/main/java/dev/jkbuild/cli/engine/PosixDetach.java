// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.engine;

import dev.jkbuild.engine.EngineTransport;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Detaches the engine role into its own POSIX session by calling {@code setsid(2)} on itself —
 * the portable version of what {@code setsid(1)} would do from the spawn side, except macOS ships
 * no such command while every POSIX libc has the syscall. After this, a Ctrl-C/SIGTERM aimed at
 * the spawning client's process group can never reach the engine.
 *
 * <p>Best-effort by design: a spawned {@link java.lang.ProcessBuilder} child is never a process
 * group leader, so the call succeeds in the lazy-spawn path; someone running {@code jk
 * --engine-server} from an interactive shell IS a group leader (job control), gets {@code EPERM},
 * and keeps their foreground semantics — which is what they asked for. Any failure leaves the
 * process exactly where it was, no worse than the pre-detach behavior; the engine's
 * SIGINT/SIGHUP-ignore policy still applies either way.
 */
public final class PosixDetach {

    private PosixDetach() {}

    /** {@code pid_t setsid(void)} — {@code pid_t} is a 32-bit int on Linux and macOS. */
    private static final FunctionDescriptor SETSID = FunctionDescriptor.of(ValueLayout.JAVA_INT);

    /** Move this process into its own session (and thus its own process group), best-effort. */
    public static void intoOwnSession() {
        if (EngineTransport.useLoopbackTcp()) return; // Windows: no POSIX sessions
        try {
            Linker linker = Linker.nativeLinker();
            MemorySegment addr = linker.defaultLookup().find("setsid").orElse(null);
            if (addr == null) return;
            MethodHandle setsid = linker.downcallHandle(addr, SETSID);
            // -1 (EPERM: already a session/group leader) is fine — see class javadoc.
            int unusedRc = (int) setsid.invokeExact();
        } catch (Throwable t) {
            // Best-effort: stay in the spawner's session, exactly the pre-detach behavior.
        }
    }
}
