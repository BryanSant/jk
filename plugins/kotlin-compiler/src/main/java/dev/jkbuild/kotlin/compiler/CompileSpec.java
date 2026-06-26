// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.kotlin.compiler;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The compile request jk hands the worker, parsed from a line-oriented spec
 * file. One {@code KEY value} per line; the key is the text up to the first
 * space, the value is the remainder verbatim (so paths may contain spaces).
 * Blank lines and {@code #} comments are ignored. Repeatable keys accumulate.
 *
 * <pre>
 *   OUTPUT &lt;dir&gt;            compiled .class destination (required)
 *   WORKDIR &lt;dir&gt;           incremental cache dir; present ⇒ incremental
 *   SNAPSHOT_DIR &lt;dir&gt;      cache dir for classpath ABI snapshots (optional)
 *   JVM_TARGET &lt;n&gt;          -jvm-target (required)
 *   MODULE_NAME &lt;name&gt;      -module-name (optional)
 *   LANGUAGE_VERSION &lt;x&gt;    -language-version (optional)
 *   API_VERSION &lt;x&gt;         -api-version (optional)
 *   SOURCE &lt;file&gt;           a .kt/.java source file (repeatable, ≥1)
 *   CLASSPATH &lt;path&gt;        a classpath entry (repeatable)
 *   FRIEND &lt;path&gt;           a friend path for internal visibility (repeatable)
 *   ARG &lt;raw&gt;               a free compiler argument, appended verbatim (repeatable)
 * </pre>
 *
 * <p>The worker stays policy-free: anything jk wants to control (e.g.
 * {@code -no-stdlib}) it passes as an {@code ARG}.
 */
final class CompileSpec {

    File outputDir;
    File workingDir; // null ⇒ non-incremental full compile
    File snapshotDir; // null ⇒ no classpath ABI snapshots
    String jvmTarget;
    String moduleName;
    String languageVersion;
    String apiVersion;
    final List<File> sources = new ArrayList<>();
    final List<File> classpath = new ArrayList<>();
    final List<File> friendPaths = new ArrayList<>();
    final List<String> extraArgs = new ArrayList<>();

    boolean incremental() {
        return workingDir != null;
    }

    static CompileSpec parse(Path specFile) throws IOException {
        CompileSpec s = new CompileSpec();
        for (String raw : Files.readAllLines(specFile, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int sp = line.indexOf(' ');
            String key = sp < 0 ? line : line.substring(0, sp);
            String val = sp < 0 ? "" : line.substring(sp + 1);
            switch (key) {
                case "OUTPUT" -> s.outputDir = new File(val);
                case "WORKDIR" -> s.workingDir = new File(val);
                case "SNAPSHOT_DIR" -> s.snapshotDir = new File(val);
                case "JVM_TARGET" -> s.jvmTarget = val;
                case "MODULE_NAME" -> s.moduleName = val;
                case "LANGUAGE_VERSION" -> s.languageVersion = val;
                case "API_VERSION" -> s.apiVersion = val;
                case "SOURCE" -> s.sources.add(new File(val));
                case "CLASSPATH" -> s.classpath.add(new File(val));
                case "FRIEND" -> s.friendPaths.add(new File(val));
                case "ARG" -> s.extraArgs.add(val);
                default -> throw new IllegalArgumentException("unknown spec key: " + key);
            }
        }
        if (s.outputDir == null) throw new IllegalArgumentException("spec missing OUTPUT");
        if (s.jvmTarget == null) throw new IllegalArgumentException("spec missing JVM_TARGET");
        if (s.sources.isEmpty()) throw new IllegalArgumentException("spec has no SOURCE entries");
        return s;
    }
}
