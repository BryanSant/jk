// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.layout;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds classes declaring a JVM-launchable {@code main} in compiled output — the fallback when
 * {@code [application] main} is absent (spring-boot plan §3.8, mirroring Boot's
 * {@code resolveMainClass}). Kotlin's {@code …Kt} facade classes need no special casing: kotlinc
 * emits a static {@code main} on them directly. Uses the JDK's {@code java.lang.classfile} API —
 * no bytecode-library dependency; we inspect the parsed method table, never string-match source.
 *
 * <p>Recognizes the full Java 25 launch protocol (JEP 512 / JLS §12.1.4), not just the legacy
 * {@code public static void main(String[])}. A method is a <em>candidate main</em> when its name is
 * {@code main}, its result is {@code void}, its access is {@code public}/{@code protected}/package
 * (never {@code private}), and it takes either a single {@code String[]} ({@code String...} compiles
 * to the same descriptor) or no parameters — {@code static} or instance. An instance candidate only
 * counts when the enclosing class is instantiable by the launcher: a concrete (non-{@code abstract},
 * non-interface) class with a non-{@code private} no-arg constructor. This also covers compact source
 * files, whose implicitly-declared class exposes {@code void main()} as an ordinary instance method
 * with a generated default constructor.
 *
 * <p>Scope is the methods <em>declared</em> in each class. A {@code main} inherited from a superclass
 * or a {@code default} interface method is spec-legal but not detected here — resolving it would mean
 * loading the whole classpath, which this per-classfile scan deliberately avoids; declare
 * {@code [application] main} for that rare case.
 */
public final class MainClassScanner {

    private static final String ARGS_DESCRIPTOR = "([Ljava/lang/String;)V";
    private static final String NOARG_DESCRIPTOR = "()V";

    private MainClassScanner() {}

    /** Every class in {@code classesDirOrJar} (a directory tree or a jar) declaring a JVM main. */
    public static List<String> scan(Path classesDirOrJar) throws IOException {
        List<String> found = new ArrayList<>();
        if (Files.isDirectory(classesDirOrJar)) {
            try (var walk = Files.walk(classesDirOrJar)) {
                for (Path p : walk.filter(f -> f.getFileName().toString().endsWith(".class"))
                        .toList()) {
                    addIfMain(found, Files.readAllBytes(p));
                }
            }
        } else if (Files.isRegularFile(classesDirOrJar)) {
            try (JarFile jar = new JarFile(classesDirOrJar.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                    try (var in = jar.getInputStream(e)) {
                        addIfMain(found, in.readAllBytes());
                    }
                }
            }
        }
        return found;
    }

    /**
     * The single main class, or an {@link IOException} whose message is ready to print: none found
     * → points at {@code [application] main}; several → lists them. Thrown as one of the two typed
     * subclasses below so callers that care (the engine's exec-plan computation) can distinguish
     * "nothing found" from "ambiguous" without parsing the message.
     */
    public static String scanUnique(Path classesDirOrJar) throws IOException {
        List<String> found = scan(classesDirOrJar);
        if (found.isEmpty()) {
            throw new NoMainFoundException("no launchable `main` method found in " + classesDirOrJar
                    + " — set `[application] main = \"...\"` in jk.toml");
        }
        if (found.size() > 1) {
            throw new AmbiguousMainException("multiple main classes found (" + String.join(", ", found)
                    + ") — pick one with `[application] main = \"...\"` in jk.toml", found);
        }
        return found.get(0);
    }

    /** No launchable {@code main} anywhere in the scanned output. */
    public static final class NoMainFoundException extends IOException {
        public NoMainFoundException(String message) {
            super(message);
        }
    }

    /** More than one candidate {@code main} class; {@link #candidates()} lists them. */
    public static final class AmbiguousMainException extends IOException {
        private final List<String> candidates;

        public AmbiguousMainException(String message, List<String> candidates) {
            super(message);
            this.candidates = candidates;
        }

        public List<String> candidates() {
            return candidates;
        }
    }

    private static void addIfMain(List<String> found, byte[] bytes) {
        ClassModel cls;
        try {
            cls = ClassFile.of().parse(bytes);
        } catch (RuntimeException e) {
            return; // unparseable/newer classfile — skip, never fail the scan
        }
        if (cls.isModuleInfo()) return;
        String name = cls.thisClass().asInternalName();
        if (name.endsWith("/package-info") || name.equals("package-info")) return;
        for (MethodModel m : cls.methods()) {
            if (isCandidateMain(cls, m)) {
                found.add(name.replace('/', '.'));
                return;
            }
        }
    }

    /**
     * Whether {@code m} is a launchable {@code main} per JLS §12.1.4 — see the class doc. Static
     * candidates need only the name/return/params/access shape; an instance candidate additionally
     * requires {@code cls} to be instantiable by the launcher (concrete, with a non-private no-arg
     * constructor), since the launcher does {@code new Cls().main(...)}.
     */
    private static boolean isCandidateMain(ClassModel cls, MethodModel m) {
        if (!m.methodName().equalsString("main")) return false;
        boolean args = m.methodType().equalsString(ARGS_DESCRIPTOR);
        if (!args && !m.methodType().equalsString(NOARG_DESCRIPTOR)) return false; // wrong params/return
        var flags = m.flags().flags();
        if (flags.contains(AccessFlag.PRIVATE)) return false; // private is never a candidate
        if (flags.contains(AccessFlag.STATIC)) return true;
        return isInstantiable(cls); // instance main → the class must be `new`-able
    }

    /** A concrete (non-abstract, non-interface) class exposing a non-private no-arg constructor. */
    private static boolean isInstantiable(ClassModel cls) {
        var classFlags = cls.flags().flags();
        if (classFlags.contains(AccessFlag.INTERFACE) || classFlags.contains(AccessFlag.ABSTRACT)) {
            return false;
        }
        for (MethodModel m : cls.methods()) {
            if (m.methodName().equalsString("<init>")
                    && m.methodType().equalsString(NOARG_DESCRIPTOR)
                    && !m.flags().flags().contains(AccessFlag.PRIVATE)) {
                return true;
            }
        }
        return false;
    }
}
