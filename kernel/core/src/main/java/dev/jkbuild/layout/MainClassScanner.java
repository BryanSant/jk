// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.layout;

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
 * Finds {@code public static void main(String[])} in compiled output — the fallback when
 * {@code [application] main} is absent (spring-boot plan §3.8, mirroring Boot's
 * {@code resolveMainClass}). Kotlin's {@code …Kt} facade classes need no special casing: kotlinc
 * emits the static {@code main} on them directly. Uses the JDK's {@code java.lang.classfile} API —
 * no bytecode-library dependency.
 */
public final class MainClassScanner {

    private static final String MAIN_DESCRIPTOR = "([Ljava/lang/String;)V";

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
     * → points at {@code [application] main}; several → lists them.
     */
    public static String scanUnique(Path classesDirOrJar) throws IOException {
        List<String> found = scan(classesDirOrJar);
        if (found.isEmpty()) {
            throw new IOException("no `public static void main` found in " + classesDirOrJar
                    + " — set `[application] main = \"...\"` in jk.toml");
        }
        if (found.size() > 1) {
            throw new IOException("multiple main classes found (" + String.join(", ", found)
                    + ") — pick one with `[application] main = \"...\"` in jk.toml");
        }
        return found.get(0);
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
            if (!m.methodName().equalsString("main")) continue;
            if (!m.methodType().equalsString(MAIN_DESCRIPTOR)) continue;
            var flags = m.flags().flags();
            if (flags.contains(AccessFlag.PUBLIC) && flags.contains(AccessFlag.STATIC)) {
                found.add(name.replace('/', '.'));
                return;
            }
        }
    }
}
