// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.java.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class InProcessJavacTest {

    /** A source-generating processor: each {@code @Gen} type yields a {@code <Name>Gen} class. */
    @Retention(RetentionPolicy.SOURCE)
    @Target(ElementType.TYPE)
    public @interface Gen {}

    @SupportedAnnotationTypes("dev.jkbuild.java.compiler.InProcessJavacTest.Gen")
    public static final class GenProcessor extends AbstractProcessor {
        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            for (Element e : round.getElementsAnnotatedWith(Gen.class)) {
                if (!(e instanceof TypeElement type)) continue;
                String pkg = processingEnv
                        .getElementUtils()
                        .getPackageOf(type)
                        .getQualifiedName()
                        .toString();
                String genName = (pkg.isEmpty() ? "" : pkg + ".") + type.getSimpleName() + "Gen";
                try {
                    JavaFileObject jfo = processingEnv.getFiler().createSourceFile(genName, type);
                    try (Writer w = jfo.openWriter()) {
                        w.write((pkg.isEmpty() ? "" : "package " + pkg + ";\n")
                                + "public class "
                                + type.getSimpleName()
                                + "Gen {}\n");
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
            return true;
        }
    }

    @Test
    void captures_generated_to_originating_source(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("src/app/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(
                src, "package app; " + "@dev.jkbuild.java.compiler.InProcessJavacTest.Gen public class Widget {}");

        Path classOut = dir.resolve("classes");
        Path genOut = dir.resolve("generated");
        // The processor's annotation type lives on the test classpath; pass it through
        // so javac can resolve @Gen.
        List<Path> classpath = testClasspath();

        InProcessJavac.Result r = InProcessJavac.compile(
                List.of(src), classpath, classOut, genOut, 21, List.of(), List.of(new GenProcessor()));

        assertThat(r.success())
                .as("compile + processing succeeded: %s", r.diagnostics())
                .isTrue();

        // Provenance: the generated WidgetGen.java is attributed to Widget.java.
        assertThat(r.generated()).hasSize(1);
        Map.Entry<Path, Set<Path>> entry = r.generated().entrySet().iterator().next();
        assertThat(entry.getKey().toString().replace('\\', '/')).endsWith("app/WidgetGen.java");
        assertThat(entry.getValue())
                .anyMatch(p -> p.toString().replace('\\', '/').endsWith("app/Widget.java"));

        // Both the original and the generated class were compiled.
        assertThat(classOut.resolve("app/Widget.class")).isRegularFile();
        assertThat(classOut.resolve("app/WidgetGen.class")).isRegularFile();
    }

    private static List<Path> testClasspath() {
        String cp = System.getProperty("java.class.path");
        List<Path> paths = new ArrayList<>();
        for (String e : cp.split(File.pathSeparator)) paths.add(Path.of(e));
        return paths;
    }
}
