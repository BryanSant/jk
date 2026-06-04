// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.java.compiler;

import javax.annotation.processing.Processor;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
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
public final class JavaCompilerWorker {

    static final String PREFIX = "##JKJC:";

    private JavaCompilerWorker() {}

    public static void main(String[] argv) {
        PrintStream out = new PrintStream(
                new FileOutputStream(FileDescriptor.out), /* autoFlush */ true, StandardCharsets.UTF_8);
        try {
            if (argv.length != 1) {
                System.err.println("usage: JavaCompilerWorker <spec-file>|@<spec-file>");
                System.exit(2);
                return;
            }
            String spec = argv[0].startsWith("@") ? argv[0].substring(1) : argv[0];
            System.exit(run(Path.of(spec), out));
        } catch (Throwable t) {
            System.err.println("jk-java-compiler: " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.err);
            System.exit(2);
        }
    }

    /** Run a compile from {@code specFile}, emitting NDJSON to {@code out}; returns the exit code. */
    public static int run(Path specFile, PrintStream out) throws Exception {
        Spec spec = Spec.parse(specFile);
        List<Processor> processors = loadProcessors(spec.processorPath);

        InProcessJavac.Result r = InProcessJavac.compile(
                spec.sources, spec.classpath, spec.classOutput, spec.sourceOutput,
                spec.release, spec.args, processors);

        for (String d : r.diagnostics()) {
            out.println(PREFIX + "{\"t\":\"diag\",\"msg\":" + quote(d) + "}");
        }
        for (Map.Entry<Path, Set<Path>> e : r.generated().entrySet()) {
            StringBuilder src = new StringBuilder("[");
            boolean first = true;
            for (Path s : e.getValue()) {
                if (!first) src.append(',');
                src.append(quote(s.toString()));
                first = false;
            }
            src.append(']');
            out.println(PREFIX + "{\"t\":\"prov\",\"gen\":" + quote(e.getKey().toString())
                    + ",\"src\":" + src + "}");
        }
        out.println(PREFIX + "{\"t\":\"result\",\"status\":\"" + (r.success() ? "OK" : "ERROR") + "\"}");
        out.flush();
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

    private static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
