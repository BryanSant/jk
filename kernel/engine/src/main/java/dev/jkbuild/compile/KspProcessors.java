// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Splits a module's {@code [processor-dependencies]} jars by processing model (android-plan §3.5).
 * The detection rule is the ecosystem's own convention — what the jar actually registers:
 *
 * <ul>
 *   <li>a {@code META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider}
 *       entry ⇒ a KSP processor — it runs in the forked KSP2 round;
 *   <li>otherwise a {@code META-INF/services/javax.annotation.processing.Processor} entry ⇒ a
 *       javac annotation processor — it stays on {@code -processorpath}.
 * </ul>
 *
 * <p>Jars carrying <em>both</em> services (Room's compiler does — one artifact serves KAPT and
 * KSP) count as KSP: running them under javac too would generate everything twice. KSP wins;
 * there is no KAPT in jk, ever (android-plan §3.1's deliberate cuts).
 */
public final class KspProcessors {

    private static final String KSP_SERVICE =
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider";

    private KspProcessors() {}

    /** The processor-path split: KSP jars and javac-only jars, in declaration order. */
    public record Split(List<Path> ksp, List<Path> javac) {}

    public static Split split(List<Path> processorPath) {
        List<Path> ksp = new ArrayList<>();
        List<Path> javac = new ArrayList<>();
        for (Path jar : processorPath) {
            if (isKspProcessor(jar)) ksp.add(jar);
            else javac.add(jar);
        }
        return new Split(ksp, javac);
    }

    /** True when {@code jar} registers a KSP {@code SymbolProcessorProvider}. */
    public static boolean isKspProcessor(Path jar) {
        if (!Files.isRegularFile(jar)) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(KSP_SERVICE);
            return entry != null;
        } catch (IOException e) {
            return false; // unreadable jar — let javac surface the real error downstream
        }
    }
}
