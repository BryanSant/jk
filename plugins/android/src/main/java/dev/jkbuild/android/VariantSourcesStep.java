// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.StepExec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * The {@code android-variant-sources} step ({@code [android.flavors.<f>] extra-src} /
 * {@code [android.build-types.<t>] extra-src}): the selected variant's extra source dirs copy
 * into a contributed sources dir, joining the compilers and the KSP round like any generated
 * source (android-plan A5e finding 18 — NiA's src/demo + src/prod flavored Hilt modules). The
 * dirs are declared step inputs, so an edit re-runs the copy and everything downstream.
 */
final class VariantSourcesStep {

    private VariantSourcesStep() {}

    static void run(StepExec exec) throws Exception {
        Path out = exec.outputDir("src");
        int copied = 0;
        for (String rel : exec.config().stringList("extra-src")) {
            Path dir = exec.moduleDir().resolve(rel);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    Path target = out.resolve(dir.relativize(file).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
        }
        exec.label("variant sources (" + copied + (copied == 1 ? " file)" : " files)"));
    }
}
