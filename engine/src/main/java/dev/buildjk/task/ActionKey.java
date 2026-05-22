// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.task;

import dev.buildjk.compile.CompileRequest;
import dev.buildjk.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@code SHA-256(action) → outputs} keys for the {@link ActionCache}.
 *
 * <p>The action key for a javac invocation is a stable hash of:
 * <ul>
 *   <li>the task identifier (e.g. {@code "compile-main"})</li>
 *   <li>jk version</li>
 *   <li>{@code --release} and any extra javac options</li>
 *   <li>each source file's SHA-256 (so editing a file invalidates the key)</li>
 *   <li>each classpath entry's path — CAS paths already incorporate the
 *       content hash, so the file name itself is enough to capture the
 *       dependency.</li>
 * </ul>
 */
public final class ActionKey {

    private ActionKey() {}

    public static String forJavac(String taskId, CompileRequest request, String jkVersion)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("release:").append(request.release()).append('\n');
        sb.append("options:");
        List<String> opts = new ArrayList<>(request.extraOptions());
        opts.sort(Comparator.naturalOrder());
        sb.append(String.join(",", opts)).append('\n');

        // Sources: include path + content hash so renaming or editing both
        // invalidate the key.
        List<Path> sortedSources = new ArrayList<>(request.sources());
        sortedSources.sort(Comparator.comparing(Path::toString));
        for (Path src : sortedSources) {
            sb.append("source:").append(src.toAbsolutePath().normalize())
                    .append(':').append(Hashing.sha256Hex(Files.readAllBytes(src)))
                    .append('\n');
        }

        // Classpath: CAS paths already include the content hash in their layout.
        List<Path> cp = new ArrayList<>(request.classpath());
        cp.sort(Comparator.comparing(Path::toString));
        for (Path entry : cp) {
            sb.append("cp:").append(entry.toAbsolutePath().normalize()).append('\n');
        }

        return Hashing.sha256Hex(sb.toString());
    }

    /** Snapshot of inputs that produced an action — for {@code jk why-rebuilt} diffs. */
    public static Map<String, String> snapshotInputs(CompileRequest request) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Path src : request.sources()) {
            result.put(src.toAbsolutePath().normalize().toString(),
                    Hashing.sha256Hex(Files.readAllBytes(src)));
        }
        for (Path cp : request.classpath()) {
            // For classpath jars we record the path; the CAS layout encodes content.
            result.put("cp:" + cp.toAbsolutePath().normalize(), "");
        }
        result.put("release", Integer.toString(request.release()));
        result.put("options", String.join(",", request.extraOptions()));
        return result;
    }
}
