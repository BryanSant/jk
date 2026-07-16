// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.kotlin.compiler;

import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.protocol.PluginSpec;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.kotlin.buildtools.api.CompilationResult;
import org.jetbrains.kotlin.buildtools.api.ExecutionPolicy;
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains;
import org.jetbrains.kotlin.buildtools.api.SourcesChanges;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration.Builder;
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation;

/**
 * Child-JVM entry point that drives the Kotlin Build Tools API.
 *
 * <p>jk launches this as {@code java -cp <worker.jar>:<kotlin-bta-closure>
 * build.jumpkick.kotlin.compiler.KotlinCompiler @&lt;spec&gt;}. The plugin reads the {@link
 * CompileSpec}, runs an in-process JVM compile (incremental when the spec carries a {@code
 * WORKDIR}), streams diagnostics back as NDJSON, and exits: {@code 0} success, {@code 1}
 * compilation error, {@code 3} OOM/internal compiler error, {@code 2} bad spec / unexpected
 * failure.
 *
 * <p>It uses the {@link KotlinToolchains} entry point (the post-2.4 BTA surface; the older {@code
 * CompilationService} flow is deprecated). It depends on nothing but the Build Tools API at compile
 * time — the implementation and the Kotlin compiler arrive on the classpath at runtime,
 * version-matched by jk, so the plugin never leaks compiler deps into jk.
 */
public final class KotlinCompiler implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-kotlin-compiler", "##JKKC:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        KcProtocol proto = new KcProtocol(out);
        try {
            if (args.size() != 1) {
                System.err.println("usage: jk-kotlin-compiler <spec-file>|@<spec-file>");
                return 2;
            }
            String specArg = args.get(0).startsWith("@") ? args.get(0).substring(1) : args.get(0);
            CompileSpec spec = CompileSpec.from(PluginSpec.read(Path.of(specArg)));
            return compile(spec, proto);
        } catch (Throwable t) {
            System.err.println("jk-kotlin-compiler: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            return 2;
        }
    }

    static int compile(CompileSpec spec, KcProtocol proto) throws Exception {
        spec.outputDir.mkdirs();

        KotlinToolchains toolchains = KotlinToolchains.loadImplementation(KotlinCompiler.class.getClassLoader());
        JvmPlatformToolchain jvm = JvmPlatformToolchain.from(toolchains);
        List<Path> sources = spec.sources.stream().map(File::toPath).toList();

        CompilationResult result;
        try (KotlinToolchains.BuildSession session = toolchains.createBuildSession()) {
            ExecutionPolicy policy = toolchains.createInProcessExecutionPolicy();
            KcLogger logger = new KcLogger(proto);

            JvmCompilationOperation.Builder op = jvm.jvmCompilationOperationBuilder(sources, spec.outputDir.toPath());
            // Destination is a first-class builder param above; everything else jk
            // controls as raw kotlinc arguments parsed into the typed argument model.
            op.getCompilerArguments().applyArgumentStrings(buildArgs(spec));
            if (!spec.plugins.isEmpty()) {
                // Compiler plugins must go through the typed COMPILER_PLUGINS argument —
                // raw -Xplugin/-P strings are silently ignored by the BTA execution path.
                List<org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin> plugins = new ArrayList<>();
                for (CompileSpec.Plugin plugin : spec.plugins) {
                    List<org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption> options =
                            new ArrayList<>();
                    for (String opt : plugin.options()) {
                        int eq = opt.indexOf('=');
                        options.add(new org.jetbrains.kotlin.buildtools.api.arguments.CompilerPluginOption(
                                eq < 0 ? opt : opt.substring(0, eq), eq < 0 ? "" : opt.substring(eq + 1)));
                    }
                    plugins.add(new org.jetbrains.kotlin.buildtools.api.arguments.CompilerPlugin(
                            plugin.id(), List.of(jarSuffixed(plugin.jar().toPath())), options, java.util.Set.of()));
                }
                op.getCompilerArguments()
                        .set(
                                org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
                                        .COMPILER_PLUGINS,
                                plugins);
            }

            if (spec.incremental()) {
                spec.workingDir.mkdirs();
                // Classpath ABI snapshots let BTA recompile precisely when a
                // dependency changes (without them it would fall back to a full
                // rebuild on any classpath change). Source edits are tracked by
                // the working dir under SourcesChanges.ToBeCalculated regardless.
                List<Path> depSnapshots =
                        spec.snapshotDir != null ? snapshotClasspath(jvm, session, policy, logger, spec) : List.of();
                Builder ic = op.snapshotBasedIcConfigurationBuilder(
                        spec.workingDir.toPath(), SourcesChanges.ToBeCalculated.INSTANCE, depSnapshots);
                ic.set(JvmSnapshotBasedIncrementalCompilationConfiguration.USE_FIR_RUNNER, Boolean.TRUE);
                op.set(JvmCompilationOperation.INCREMENTAL_COMPILATION, ic.build());
            }

            result = session.executeOperation(op.build(), policy, logger);
        }

        proto.result(result.name());
        int exit = switch (result) {
            case COMPILATION_SUCCESS -> 0;
            case COMPILATION_ERROR -> 1;
            default -> 3; // COMPILATION_OOM_ERROR, COMPILER_INTERNAL_ERROR
        };
        proto.done(exit);
        return exit;
    }

    /**
     * Compute (and cache) a classpath ABI snapshot file per compile-classpath entry, returning the
     * snapshot file list for the IC config. Snapshots are keyed by the entry's path — jk's classpath
     * entries are content-addressed (CAS) paths, so a given file is immutable and its snapshot can be
     * reused across builds. On any failure we degrade to no snapshots (still source-incremental),
     * preserving the pre-snapshot behaviour.
     */
    private static List<Path> snapshotClasspath(
            JvmPlatformToolchain jvm,
            KotlinToolchains.BuildSession session,
            ExecutionPolicy policy,
            org.jetbrains.kotlin.buildtools.api.KotlinLogger logger,
            CompileSpec spec) {
        try {
            Path dir = spec.snapshotDir.toPath();
            Files.createDirectories(dir);
            List<Path> out = new ArrayList<>(spec.classpath.size());
            for (File entry : spec.classpath) {
                if (!entry.exists()) continue;
                Path snapshot = dir.resolve(snapshotName(entry));
                if (!Files.isRegularFile(snapshot)) {
                    org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmClasspathSnapshottingOperation snapOp =
                            jvm.classpathSnapshottingOperationBuilder(entry.toPath())
                                    .build();
                    org.jetbrains.kotlin.buildtools.api.jvm.ClasspathEntrySnapshot computed =
                            session.executeOperation(snapOp, policy, logger);
                    computed.saveSnapshot(snapshot);
                }
                out.add(snapshot);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Stable snapshot filename for a classpath entry (its path is content-unique in the CAS). */
    private static String snapshotName(File entry) throws java.security.NoSuchAlgorithmException {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(entry.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
        return sb.append(".snapshot").toString();
    }

    /**
     * Translate the spec into raw Kotlin compiler arguments (destination excluded — it's a builder
     * parameter). The plugin sets only what the spec describes; jk owns all policy (e.g. {@code
     * -no-stdlib}) via {@code ARG} entries appended verbatim at the end.
     */
    static List<String> buildArgs(CompileSpec spec) {
        List<String> args = new ArrayList<>();
        args.add("-jvm-target");
        args.add(spec.jvmTarget);
        if (spec.moduleName != null) {
            args.add("-module-name");
            args.add(spec.moduleName);
        }
        if (spec.languageVersion != null) {
            args.add("-language-version");
            args.add(spec.languageVersion);
        }
        if (spec.apiVersion != null) {
            args.add("-api-version");
            args.add(spec.apiVersion);
        }
        if (!spec.classpath.isEmpty()) {
            args.add("-classpath");
            args.add(join(spec.classpath, File.pathSeparator));
        }
        if (!spec.friendPaths.isEmpty()) {
            args.add("-Xfriend-paths=" + join(spec.friendPaths, ","));
        }
        if (spec.incremental()) {
            // Required whenever the FIR (K2) incremental runner is selected.
            args.add("-Xuse-fir-ic");
        }
        args.addAll(spec.extraArgs);
        return args;
    }

    /**
     * The compiler's plugin loader silently ignores classpath entries that don't end in
     * {@code .jar} — jk's CAS blob paths carry no extension, so hard-link (or copy) the jar to a
     * {@code .jar}-suffixed temp file. Silent is the operative word: nothing fails, the plugin
     * just never runs.
     */
    private static java.nio.file.Path jarSuffixed(java.nio.file.Path jar) throws java.io.IOException {
        if (jar.getFileName().toString().endsWith(".jar")) return jar;
        java.nio.file.Path suffixed =
                java.nio.file.Files.createTempFile("jk-kotlin-plugin-", ".jar");
        java.nio.file.Files.delete(suffixed); // createLink needs the target absent
        try {
            java.nio.file.Files.createLink(suffixed, jar);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            java.nio.file.Files.copy(jar, suffixed); // cross-device: copy instead
        }
        suffixed.toFile().deleteOnExit();
        return suffixed;
    }

    private static String join(List<File> files, String sep) {
        return files.stream().map(File::getAbsolutePath).collect(Collectors.joining(sep));
    }
}
