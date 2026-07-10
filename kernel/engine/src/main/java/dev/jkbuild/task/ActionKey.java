// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.task;

import dev.jkbuild.compile.CompileRequest;
import dev.jkbuild.compile.KotlincRequest;
import dev.jkbuild.util.Hashing;
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
 *
 * <ul>
 *   <li>the task identifier (e.g. {@code "compile-main"})
 *   <li>jk version
 *   <li>{@code --release} and any extra javac options
 *   <li>each source file's SHA-256 (so editing a file invalidates the key)
 *   <li>each classpath entry's path — CAS paths already incorporate the content hash, so the file
 *       name itself is enough to capture the dependency.
 * </ul>
 */
public final class ActionKey {

    private ActionKey() {}

    public static String forJavac(String taskId, CompileRequest request, String jkVersion) throws IOException {
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
            sb.append("source:")
                    .append(src.toAbsolutePath().normalize())
                    .append(':')
                    .append(Hashing.sha256Hex(Files.readAllBytes(src)))
                    .append('\n');
        }

        // Classpath: CAS paths already include the content hash in their layout.
        List<Path> cp = new ArrayList<>(request.classpath());
        cp.sort(Comparator.comparing(Path::toString));
        for (Path entry : cp) {
            sb.append("cp:").append(entry.toAbsolutePath().normalize()).append('\n');
        }

        // Processor path: a processor change can regenerate everything, so it must
        // invalidate the key (CAS paths encode the processor jar's content).
        List<Path> pp = new ArrayList<>(request.processorPath());
        pp.sort(Comparator.comparing(Path::toString));
        for (Path entry : pp) {
            sb.append("pp:").append(entry.toAbsolutePath().normalize()).append('\n');
        }

        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * Action key for a Kotlin worker invocation. Same shape as {@link #forJavac}: task + jk version +
     * jvm target + sorted free args + each source's content hash + classpath paths (both the
     * compilation classpath and the worker's Build Tools API closure — whose CAS paths encode the
     * compiler version, so a compiler bump invalidates the key).
     */
    public static String forKotlinc(String taskId, KotlincRequest request, String jkVersion) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        sb.append("jvmTarget:").append(request.jvmTarget()).append('\n');
        sb.append("args:");
        List<String> args = new ArrayList<>(request.extraArgs());
        args.sort(Comparator.naturalOrder());
        sb.append(String.join(",", args)).append('\n');

        List<Path> sortedSources = new ArrayList<>(request.sources());
        sortedSources.sort(Comparator.comparing(Path::toString));
        for (Path src : sortedSources) {
            sb.append("source:")
                    .append(src.toAbsolutePath().normalize())
                    .append(':')
                    .append(Hashing.sha256Hex(Files.readAllBytes(src)))
                    .append('\n');
        }

        List<Path> cp = new ArrayList<>(request.classpath());
        cp.addAll(request.workerClasspath());
        cp.sort(Comparator.comparing(Path::toString));
        for (Path entry : cp) {
            sb.append("cp:").append(entry.toAbsolutePath().normalize()).append('\n');
        }
        return Hashing.sha256Hex(sb.toString());
    }

    /**
     * Action key for a packaging artifact (jar, fat-jar, native binary, OCI tarball, …). A stable
     * hash of the task id, jk version, and a set of pre-computed input tokens — typically {@link
     * ClasspathFingerprint} hashes of the input classes/jars plus config strings (main-class,
     * manifest, build args, toolchain version, …). The caller MUST include every input that affects
     * the produced bytes; a missing token risks serving a stale artifact.
     */
    public static String forArtifact(String taskId, String jkVersion, List<String> inputTokens) {
        StringBuilder sb = new StringBuilder();
        sb.append("task:").append(taskId).append('\n');
        sb.append("jk:").append(jkVersion).append('\n');
        List<String> sorted = new ArrayList<>(inputTokens);
        sorted.sort(Comparator.naturalOrder());
        for (String t : sorted) sb.append("in:").append(t).append('\n');
        return Hashing.sha256Hex(sb.toString());
    }

    /** Snapshot of inputs that produced an action — for {@code jk why-rebuilt} diffs. */
    public static Map<String, String> snapshotInputs(CompileRequest request) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Path src : request.sources()) {
            result.put(src.toAbsolutePath().normalize().toString(), Hashing.sha256Hex(Files.readAllBytes(src)));
        }
        for (Path cp : request.classpath()) {
            // For classpath jars we record the path; the CAS layout encodes content.
            result.put("cp:" + cp.toAbsolutePath().normalize(), "");
        }
        for (Path pp : request.processorPath()) {
            result.put("pp:" + pp.toAbsolutePath().normalize(), "");
        }
        result.put("release", Integer.toString(request.release()));
        result.put("options", String.join(",", request.extraOptions()));
        return result;
    }

    /**
     * Qualify a base task id (e.g. {@code compile-main}) with a stable tag derived from a
     * module-unique directory (the compile output dir), so the {@link ActionCache} {@code
     * tasks/<taskId>} pointer doesn't collide across projects or workspace modules that share the
     * same base task name. The action key itself is already project-unique (it hashes absolute source
     * and classpath paths); this only disambiguates the per-task pointer.
     */
    public static String qualifiedTaskId(String base, Path moduleDir) {
        return base + "@" + taskTag(moduleDir);
    }

    /**
     * The 12-hex-char tag {@link #qualifiedTaskId} appends after {@code @} — a stable hash of a
     * module-unique directory. Exposed so cache maintenance ({@code jk cache clear}) can recompute
     * the tags for a project's output dirs and match every {@code tasks/<base>@<tag>} pointer that
     * belongs to it, regardless of the base task name.
     */
    public static String taskTag(Path moduleDir) {
        return Hashing.sha256Hex(moduleDir.toAbsolutePath().normalize().toString()).substring(0, 12);
    }
}
