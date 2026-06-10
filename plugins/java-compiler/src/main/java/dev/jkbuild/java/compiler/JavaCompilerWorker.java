// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.java.compiler;

import dev.jkbuild.plugin.Plugin;
import dev.jkbuild.plugin.PluginManifest;
import dev.jkbuild.plugin.protocol.Ndjson;
import dev.jkbuild.plugin.protocol.ProtocolWriter;

import javax.annotation.processing.Processor;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Child-JVM entry point that runs {@link InProcessJavac} under the project's JDK
 * and streams the result — diagnostics, annotation-processing provenance, and a
 * terminal status — back to jk as NDJSON ({@value #PREFIX}).
 *
 * <p>jk launches it as {@code <javaHome>/bin/java -cp <worker.jar>
 * dev.jkbuild.java.compiler.JavaCompilerWorker @<spec>}. Processors are
 * discovered from the spec's processor path (the same ServiceLoader mechanism
 * javac uses) so they can be wrapped for Filer provenance capture.
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
public final class JavaCompilerWorker implements Plugin {

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
        Spec spec = Spec.parse(specFile);
        List<Processor> processors = loadProcessors(spec.processorPath);

        InProcessJavac.Result r = InProcessJavac.compile(
                spec.sources, spec.classpath, spec.classOutput, spec.sourceOutput,
                spec.release, spec.args, processors);

        for (String d : r.diagnostics()) {
            out.emit("{\"t\":\"diag\",\"msg\":" + Ndjson.quote(d) + "}");
        }
        for (Map.Entry<Path, Set<Path>> e : r.generated().entrySet()) {
            StringBuilder src = new StringBuilder("[");
            boolean first = true;
            for (Path s : e.getValue()) {
                if (!first) src.append(',');
                src.append(Ndjson.quote(s.toString()));
                first = false;
            }
            src.append(']');
            out.emit("{\"t\":\"prov\",\"gen\":" + Ndjson.quote(e.getKey().toString())
                    + ",\"src\":" + src + "}");
        }
        out.emit("{\"t\":\"result\",\"status\":\"" + (r.success() ? "OK" : "ERROR") + "\"}");
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
        URLClassLoader loader = new URLClassLoader(urls, JavaCompilerWorker.class.getClassLoader());
        List<Processor> processors = new ArrayList<>();
        for (Processor p : ServiceLoader.load(Processor.class, loader)) processors.add(p);
        return processors;
    }

    private record Spec(Path classOutput, Path sourceOutput, int release, List<Path> sources,
                        List<Path> classpath, List<Path> processorPath, List<String> args) {
        static Spec parse(Path file) throws java.io.IOException {
            Path classOutput = null;
            Path sourceOutput = null;
            int release = -1;
            List<Path> sources = new ArrayList<>();
            List<Path> classpath = new ArrayList<>();
            List<Path> processorPath = new ArrayList<>();
            List<String> args = new ArrayList<>();
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int sp = line.indexOf(' ');
                String key = sp < 0 ? line : line.substring(0, sp);
                String val = sp < 0 ? "" : line.substring(sp + 1);
                switch (key) {
                    case "CLASSOUTPUT" -> classOutput = Path.of(val);
                    case "SOURCEOUTPUT" -> sourceOutput = Path.of(val);
                    case "RELEASE" -> release = Integer.parseInt(val);
                    case "SOURCE" -> sources.add(Path.of(val));
                    case "CLASSPATH" -> classpath.add(Path.of(val));
                    case "PROCESSORPATH" -> processorPath.add(Path.of(val));
                    case "ARG" -> args.add(val);
                    default -> throw new IllegalArgumentException("unknown spec key: " + key);
                }
            }
            if (classOutput == null) throw new IllegalArgumentException("spec missing CLASSOUTPUT");
            if (sourceOutput == null) throw new IllegalArgumentException("spec missing SOURCEOUTPUT");
            if (release < 0) throw new IllegalArgumentException("spec missing RELEASE");
            if (sources.isEmpty()) throw new IllegalArgumentException("spec has no SOURCE entries");
            return new Spec(classOutput, sourceOutput, release, sources, classpath, processorPath, args);
        }
    }

}
