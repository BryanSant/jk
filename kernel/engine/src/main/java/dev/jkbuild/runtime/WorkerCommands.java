// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import dev.jkbuild.jdk.HostPlatform;
import dev.jkbuild.jdk.JavaHomes;
import dev.jkbuild.worker.JvmOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code java -jar <worker>.jar <spec>} command line every plugin-worker fork uses —
 * the JVM located from the running process's own java home, heap-sized through {@link JvmOptions}'
 * shared plan (in the resident engine, the plan {@code EngineServer.planSharedWorkerMemoryOnce}
 * computed at startup). Shared by the {@code *Goals} factories so no fork site hand-rolls it.
 */
final class WorkerCommands {

    private WorkerCommands() {}

    /** {@code java [extraJvmArgs] -jar workerJar spec}, heap-sized for one requested JVM. */
    static List<String> javaCommand(Path workerJar, List<String> extraJvmArgs, Path spec) {
        Path javaExe = JavaHomes.runningJavaHome()
                .resolve("bin")
                .resolve(HostPlatform.isWindows() ? "java.exe" : "java");
        List<String> rest = new ArrayList<>(extraJvmArgs);
        rest.add("-jar");
        rest.add(workerJar.toString());
        rest.add(spec.toAbsolutePath().toString());
        return JvmOptions.javaCommand(javaExe.toString(), 1, rest);
    }

    /** {@code java -jar workerJar spec} with no extra JVM args. */
    static List<String> javaCommand(Path workerJar, Path spec) {
        return javaCommand(workerJar, List.of(), spec);
    }
}
