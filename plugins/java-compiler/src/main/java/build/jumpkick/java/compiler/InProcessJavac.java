// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.java.compiler;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Runs {@code javac} in-process via the {@link JavacTask} API and captures annotation-processing
 * <em>provenance</em>: for each source file an annotation processor generated, the input source
 * file(s) it originated from. That mapping — unavailable from a plain {@code javac} subprocess — is
 * what lets the incremental compiler re-run a processor for just the changed originators instead of
 * recompiling the world.
 *
 * <p>Capture works by wrapping each {@link Processor}: on {@code init} we hand the real processor a
 * {@link ProcessingEnvironment} whose {@link Filer} records the {@code originatingElements} passed
 * to {@code createSourceFile} / {@code createClassFile}, resolved to their source files via {@link
 * Trees}.
 *
 * <p>Depends only on the JDK compiler APIs, so it runs in the {@code :java-compiler} plugin under
 * the project's JDK (never in jk's own native-image process).
 */
public final class InProcessJavac {

    private InProcessJavac() {}

    /**
     * @param generated generated source file → the input source files it came from (only
     *     source-resolvable originating elements are recorded)
     */
    public record Result(boolean success, List<Diag> diagnostics, Map<Path, Set<Path>> generated) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /**
     * One javac diagnostic, ready for the parent to re-surface: {@code kind} is the {@code
     * javax.tools.Diagnostic.Kind} name (severity); {@code message} already carries the {@code
     * file:line:} location prefix so the parent doesn't need the structured coordinates over the
     * wire.
     */
    public record Diag(String kind, String file, long line, long col, String message) {}

    private static Diag toDiag(Diagnostic<? extends JavaFileObject> d) {
        JavaFileObject src = d.getSource();
        String file = src != null ? Path.of(src.toUri()).toString() : null;
        long line = d.getLineNumber() != Diagnostic.NOPOS ? d.getLineNumber() : 0;
        long col = d.getColumnNumber() != Diagnostic.NOPOS ? d.getColumnNumber() : 0;
        return new Diag(d.getKind().name(), file, line, col, d.getMessage(Locale.ROOT));
    }

    /**
     * Compile {@code sources} into {@code classOutput}, generating any processor output into {@code
     * sourceOutput}.
     */
    public static Result compile(
            List<Path> sources,
            List<Path> classpath,
            Path classOutput,
            Path sourceOutput,
            int release,
            List<String> options,
            List<Processor> processors) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("no system javac (run under a JDK)");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        Provenance provenance = new Provenance();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, Locale.ROOT, StandardCharsets.UTF_8)) {
            Files.createDirectories(classOutput);
            Files.createDirectories(sourceOutput);
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOutput));
            fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(sourceOutput));
            fm.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);

            List<String> opts = new ArrayList<>();
            opts.add("--release");
            opts.add(Integer.toString(release));
            opts.addAll(options);

            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) javac.getTask(null, fm, diags, opts, null, units);

            List<Processor> wrapped = new ArrayList<>(processors.size());
            for (Processor p : processors) wrapped.add(new RecordingProcessor(p, provenance));
            task.setProcessors(wrapped);

            boolean ok = task.call();
            List<Diag> messages = new ArrayList<>();
            boolean errors = false;
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                messages.add(toDiag(d));
                if (d.getKind() == Diagnostic.Kind.ERROR) errors = true;
            }
            return new Result(ok && !errors, messages, provenance.generated);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Accumulates generated-file → originating-source mappings across processing rounds. */
    private static final class Provenance {
        final Map<Path, Set<Path>> generated = new LinkedHashMap<>();
        volatile Trees trees;

        void record(FileObject created, Element[] originating) {
            if (trees == null) return;
            Path gen;
            try {
                gen = Path.of(created.toUri());
            } catch (RuntimeException notAPath) {
                return;
            }
            Set<Path> origins = generated.computeIfAbsent(gen, k -> new TreeSet<>());
            for (Element e : originating) {
                TreePath tp = trees.getPath(e);
                if (tp != null)
                    origins.add(Path.of(tp.getCompilationUnit().getSourceFile().toUri()));
            }
        }
    }

    /** Wraps a processor so the env it sees hands out a recording {@link Filer}. */
    private static final class RecordingProcessor implements Processor {
        private final Processor delegate;
        private final Provenance provenance;

        RecordingProcessor(Processor delegate, Provenance provenance) {
            this.delegate = delegate;
            this.provenance = provenance;
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public void init(ProcessingEnvironment env) {
            provenance.trees = Trees.instance(env);
            delegate.init(new RecordingEnv(env, new RecordingFiler(env.getFiler(), provenance)));
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            return delegate.process(annotations, roundEnv);
        }

        @Override
        public Iterable<? extends javax.annotation.processing.Completion> getCompletions(
                Element element, AnnotationMirror annotation, ExecutableElement module, String userText) {
            return delegate.getCompletions(element, annotation, module, userText);
        }
    }

    /** {@link ProcessingEnvironment} that swaps in the recording filer; everything else delegates. */
    private record RecordingEnv(ProcessingEnvironment delegate, Filer filer) implements ProcessingEnvironment {
        @Override
        public Map<String, String> getOptions() {
            return delegate.getOptions();
        }

        @Override
        public Messager getMessager() {
            return delegate.getMessager();
        }

        @Override
        public Filer getFiler() {
            return filer;
        }

        @Override
        public Elements getElementUtils() {
            return delegate.getElementUtils();
        }

        @Override
        public Types getTypeUtils() {
            return delegate.getTypeUtils();
        }

        @Override
        public SourceVersion getSourceVersion() {
            return delegate.getSourceVersion();
        }

        @Override
        public Locale getLocale() {
            return delegate.getLocale();
        }
    }

    /** {@link Filer} that records originating elements before delegating file creation. */
    private record RecordingFiler(Filer delegate, Provenance provenance) implements Filer {
        @Override
        public JavaFileObject createSourceFile(CharSequence name, Element... originating) throws IOException {
            JavaFileObject jfo = delegate.createSourceFile(name, originating);
            provenance.record(jfo, originating);
            return jfo;
        }

        @Override
        public JavaFileObject createClassFile(CharSequence name, Element... originating) throws IOException {
            JavaFileObject jfo = delegate.createClassFile(name, originating);
            provenance.record(jfo, originating);
            return jfo;
        }

        @Override
        public FileObject createResource(
                JavaFileManager.Location location,
                CharSequence moduleAndPkg,
                CharSequence relativeName,
                Element... originating)
                throws IOException {
            FileObject fo = delegate.createResource(location, moduleAndPkg, relativeName, originating);
            provenance.record(fo, originating);
            return fo;
        }

        @Override
        public FileObject getResource(
                JavaFileManager.Location location, CharSequence moduleAndPkg, CharSequence relativeName)
                throws IOException {
            return delegate.getResource(location, moduleAndPkg, relativeName);
        }
    }
}
