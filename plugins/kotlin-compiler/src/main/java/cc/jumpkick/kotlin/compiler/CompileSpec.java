// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.kotlin.compiler;

import cc.jumpkick.plugin.protocol.PluginSpec;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The compile request jk hands the plugin, parsed from a line-oriented spec file. One {@code KEY
 * value} per line; the key is the text up to the first space, the value is the remainder verbatim
 * (so paths may contain spaces). Blank lines and {@code #} comments are ignored. Repeatable keys
 * accumulate.
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
 * <p>The plugin stays policy-free: anything jk wants to control (e.g. {@code -no-stdlib}) it passes
 * as an {@code ARG}.
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

    /** Parsed {@code PLUGIN id\tjar\topt=val...} lines — typed compiler plugins. */
    record Plugin(String id, File jar, List<String> options) {}

    final List<Plugin> plugins = new ArrayList<>();

    boolean incremental() {
        return workingDir != null;
    }

    static CompileSpec from(PluginSpec spec) {
        CompileSpec s = new CompileSpec();
        var c = spec.config();
        if (spec.classesDir() != null) s.outputDir = spec.classesDir().toFile();
        if (spec.workdir() != null) s.workingDir = spec.workdir().toFile(); // present ⇒ incremental
        if (spec.snapshotDir() != null) s.snapshotDir = spec.snapshotDir().toFile();
        s.jvmTarget = c.stringOpt("jvmTarget").orElse(null);
        s.moduleName = c.stringOpt("moduleName").orElse(null);
        s.languageVersion = c.stringOpt("languageVersion").orElse(null);
        s.apiVersion = c.stringOpt("apiVersion").orElse(null);
        for (Path p : spec.sources()) s.sources.add(p.toFile());
        for (Path p : spec.compileClasspath()) s.classpath.add(p.toFile());
        for (Path p : spec.friendPaths()) s.friendPaths.add(p.toFile());
        s.extraArgs.addAll(spec.args());
        for (PluginSpec.CompilerPlugin cp : spec.compilerPlugins()) {
            s.plugins.add(new Plugin(cp.id(), cp.jar().toFile(), cp.options()));
        }
        if (s.outputDir == null) throw new IllegalArgumentException("spec missing layout.classesDir (OUTPUT)");
        if (s.jvmTarget == null) throw new IllegalArgumentException("spec missing config jvmTarget");
        if (s.sources.isEmpty()) throw new IllegalArgumentException("spec has no source entries");
        return s;
    }
}
