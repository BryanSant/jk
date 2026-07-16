// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.java.compiler;

import build.jumpkick.plugin.Plugin;
import build.jumpkick.plugin.PluginManifest;
import build.jumpkick.plugin.protocol.PluginReply;
import build.jumpkick.plugin.protocol.PluginSpec;
import build.jumpkick.plugin.protocol.ProtocolWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.processing.Processor;

/**
 * Child-JVM entry point that runs {@link InProcessJavac} under the project's JDK and streams the
 * result — diagnostics, annotation-processing provenance, and a terminal status — back to jk as
 * NDJSON ({@value #PREFIX}).
 *
 * <p>jk launches it as {@code <javaHome>/bin/java -cp <worker.jar>
 * build.jumpkick.java.compiler.JavaIncrementalCompiler @<spec>}. Processors are discovered from the spec's
 * processor path (the same ServiceLoader mechanism javac uses) so they can be wrapped for Filer
 * provenance capture.
 *
 * <pre>
 *   CLASSOUTPUT &lt;dir&gt;     compiled .class destination (required)
 *   SOURCEOUTPUT &lt;dir&gt;    generated-source destination (required)
 *   RELEASE &lt;n&gt;           --release (required)
 *   SOURCE &lt;file&gt;         a .java source (repeatable, ≥1)
 *   CLASSPATH &lt;path&gt;      compile classpath entry (repeatable)
 *   PROCESSORPATH &lt;path&gt;  annotation-processor classpath entry (repeatable)
 *   ARG &lt;raw&gt;             extra javac argument (repeatable)
 * </pre>
 */
public final class JavaIncrementalCompiler implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-java-compiler", "##JKJC:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) throws Exception {
        if (args.size() != 1) {
            System.err.println("usage: jk-java-compiler <spec-file>|@<spec-file>");
            return 2;
        }
        String specArg = args.get(0);
        String spec = specArg.startsWith("@") ? specArg.substring(1) : specArg;
        return compileSpec(Path.of(spec), out);
    }

    /** Run a compile from {@code specFile}, emitting NDJSON to {@code out}; returns the exit code. */
    static int compileSpec(Path specFile, ProtocolWriter out) throws Exception {
        PluginSpec spec = PluginSpec.read(specFile);
        List<Processor> processors = loadProcessors(spec.processorClasspath());

        InProcessJavac.Result r = InProcessJavac.compile(
                spec.sources(),
                spec.compileClasspath(),
                spec.classesDir(),
                spec.sourceOutput(),
                (int) spec.config().intValue("release", 0),
                spec.args(),
                processors);

        for (InProcessJavac.Diag d : r.diagnostics()) {
            out.emit(PluginReply.diagnostic(d.kind(), d.file(), (int) d.line(), (int) d.col(), d.message()));
        }
        for (Map.Entry<Path, Set<Path>> e : r.generated().entrySet()) {
            out.emit(PluginReply.provenance(
                    e.getKey().toString(), e.getValue().stream().map(Path::toString).toList()));
        }
        out.emit(PluginReply.result(Map.of("status", r.success() ? "OK" : "ERROR")));
        out.emit(PluginReply.done(r.success() ? 0 : 1));
        return r.success() ? 0 : 1;
    }

    /** ServiceLoader-discover annotation processors from the processor path (jars/dirs). */
    private static List<Processor> loadProcessors(List<Path> processorPath) {
        if (processorPath.isEmpty()) return List.of();
        URL[] urls = new URL[processorPath.size()];
        for (int i = 0; i < processorPath.size(); i++) {
            try {
                urls[i] = processorPath.get(i).toUri().toURL();
            } catch (java.net.MalformedURLException e) {
                throw new IllegalArgumentException("bad processor path entry: " + processorPath.get(i), e);
            }
        }
        URLClassLoader loader = new URLClassLoader(urls, JavaIncrementalCompiler.class.getClassLoader());
        List<Processor> processors = new ArrayList<>();
        for (Processor p : ServiceLoader.load(Processor.class, loader)) processors.add(p);
        return processors;
    }

}
