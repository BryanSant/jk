// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.compile.incremental;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles small Java sources to {@code .class} bytes via the in-JVM javac (tests run under a JDK).
 */
public final class JavacFixture {

    private JavacFixture() {}

    /**
     * Compile {@code fqcn → source} together; return {@code fqcn → class bytes} for every produced
     * class.
     */
    public static Map<String, byte[]> compile(Path work, Map<String, String> sources) throws IOException {
        Path src = work.resolve("src");
        Path out = work.resolve("out");
        Files.createDirectories(out);
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
            files.add(f.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("no system javac (run under a JDK)");
        List<String> args = new ArrayList<>(List.of("-d", out.toString()));
        args.addAll(files);
        int rc = javac.run(null, null, null, args.toArray(new String[0]));
        if (rc != 0) throw new IllegalStateException("javac failed, rc=" + rc);

        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> walk = Files.walk(out)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!p.toString().endsWith(".class")) continue;
                String rel = out.relativize(p).toString().replace(File.separatorChar, '/');
                classes.put(
                        rel.substring(0, rel.length() - ".class".length()).replace('/', '.'), Files.readAllBytes(p));
            }
        }
        return classes;
    }

    /** Convenience: compile one self-contained class and return its bytes. */
    public static byte[] compileOne(Path work, String fqcn, String source) throws IOException {
        return compile(work, Map.of(fqcn, source)).get(fqcn);
    }
}
