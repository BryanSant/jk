// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.java.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the worker's protocol end-to-end (in-process via {@link JavaCompilerWorker#run}):
 * a real annotation processor is ServiceLoader-discovered from a processor path,
 * runs, and its generated-file provenance is reported as NDJSON.
 */
class JavaCompilerWorkerTest {

    @Test
    void compiles_with_processor_and_reports_provenance(@TempDir Path dir) throws Exception {
        // A standalone annotation processor + annotation, compiled into procDir and
        // registered via META-INF/services so the worker discovers it.
        Path procDir = dir.resolve("proc");
        compile(procDir, Map.of(
                "gen.Gen", """
                        package gen;
                        import java.lang.annotation.*;
                        @Retention(RetentionPolicy.SOURCE) @Target(ElementType.TYPE)
                        public @interface Gen {}
                        """,
                "gen.GenProc", """
                        package gen;
                        import javax.annotation.processing.*;
                        import javax.lang.model.SourceVersion;
                        import javax.lang.model.element.*;
                        import javax.tools.JavaFileObject;
                        import java.io.*;
                        import java.util.Set;
                        @SupportedAnnotationTypes("gen.Gen")
                        public class GenProc extends AbstractProcessor {
                            public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                            public boolean process(Set<? extends TypeElement> a, RoundEnvironment r) {
                                for (Element e : r.getElementsAnnotatedWith(Gen.class)) {
                                    if (!(e instanceof TypeElement t)) continue;
                                    String pkg = processingEnv.getElementUtils().getPackageOf(t).getQualifiedName().toString();
                                    String name = (pkg.isEmpty()?"":pkg+".") + t.getSimpleName() + "Gen";
                                    try {
                                        JavaFileObject f = processingEnv.getFiler().createSourceFile(name, t);
                                        try (Writer w = f.openWriter()) {
                                            w.write((pkg.isEmpty()?"":"package "+pkg+";\\n") + "public class " + t.getSimpleName() + "Gen {}\\n");
                                        }
                                    } catch (IOException ex) { throw new UncheckedIOException(ex); }
                                }
                                return true;
                            }
                        }
                        """));
        Path services = procDir.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(services.getParent());
        Files.writeString(services, "gen.GenProc\n");

        Path src = dir.resolve("src/app/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package app; @gen.Gen public class Widget {}");

        Path classOut = dir.resolve("classes");
        Path genOut = dir.resolve("gen-src");
        Path spec = dir.resolve("spec.txt");
        Files.writeString(spec, String.join("\n",
                "CLASSOUTPUT " + classOut,
                "SOURCEOUTPUT " + genOut,
                "RELEASE 21",
                "SOURCE " + src.toAbsolutePath(),
                "CLASSPATH " + procDir,        // so javac resolves @gen.Gen
                "PROCESSORPATH " + procDir) + "\n");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int code = JavaCompilerWorker.run(spec, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String out = buf.toString(StandardCharsets.UTF_8);

        assertThat(code).isZero();
        assertThat(out).contains("\"t\":\"result\",\"status\":\"OK\"");
        assertThat(out).contains("\"t\":\"prov\"");
        assertThat(out).contains("WidgetGen");
        assertThat(out).contains("Widget.java");
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();
    }

    private static void compile(Path outDir, Map<String, String> sources) throws IOException {
        Path srcDir = outDir.resolve("_src");
        Files.createDirectories(outDir);
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = srcDir.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("-d", outDir.toString()));
        args.addAll(files);
        int rc = javac.run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) throw new IllegalStateException("fixture javac failed, rc=" + rc);
    }
}
