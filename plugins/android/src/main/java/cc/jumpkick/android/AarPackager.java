// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.PackageIo;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The {@code aar} packager for {@code [android] library = true} modules: the standard AAR layout —
 * {@code AndroidManifest.xml} (merged), {@code classes.jar} (the module's compiled classes,
 * {@code R} classes excluded exactly as AGP excludes them: consumers regenerate {@code R} with
 * final ids at app link time), raw {@code res/}, and {@code R.txt} (the symbol table consumers'
 * non-transitive {@code R} generation reads).
 *
 * <p>It also emits the conventional classes jar next to the AAR — the host-classpath view
 * workspace siblings compile and test against; both cache under the packager's one action key.
 */
final class AarPackager {

    private AarPackager() {}

    static void produce(PackageIo io) throws Exception {
        Path aar = io.artifactPath();
        Files.createDirectories(aar.getParent());
        Path resStep = io.stepOutput("android-res").orElse(null);
        Path manifestStep = io.stepOutput("android-manifest").orElse(null);
        Path manifest = manifestStep == null ? null : manifestStep.resolve("merged/AndroidManifest.xml");
        if (manifest == null || !Files.isRegularFile(manifest)) {
            throw new IllegalStateException("aar packaging needs the merged manifest — android-manifest did not run");
        }

        // classes.jar (R classes excluded) — written once, reused inside the AAR and as the
        // conventional sibling jar.
        String aarName = aar.getFileName().toString();
        Path classesJar = aar.resolveSibling(aarName.substring(0, aarName.length() - ".aar".length()) + ".jar");
        io.label("classes.jar");
        writeClassesJar(io.classesDir(), classesJar);

        io.label(aarName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(aar))) {
            putFile(zip, "AndroidManifest.xml", manifest);
            putFile(zip, "classes.jar", classesJar);
            if (resStep != null) {
                Path rTxt = resStep.resolve("packaged/R.txt");
                if (Files.isRegularFile(rTxt)) putFile(zip, "R.txt", rTxt);
                Path rawRes = resStep.resolve("raw-res");
                if (Files.isDirectory(rawRes)) {
                    for (Path file : ResourceStep.filesUnder(rawRes, "")) {
                        putFile(zip, "res/" + rawRes.relativize(file).toString().replace('\\', '/'), file);
                    }
                }
            }
        }
    }

    /** Jar the classes dir, excluding the generated {@code R} / {@code R$*} classes. */
    private static void writeClassesJar(Path classesDir, Path jar) throws IOException {
        List<Path> files = ResourceStep.filesUnder(classesDir, "");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Path file : files) {
                String rel = classesDir.relativize(file).toString().replace('\\', '/');
                String name = file.getFileName().toString();
                if (name.equals("R.class") || (name.startsWith("R$") && name.endsWith(".class"))) continue;
                putFile(zip, rel, file);
            }
        }
    }

    private static void putFile(ZipOutputStream zip, String entryName, Path file) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(318240000000L); // fixed stamp — reproducible AARs, same posture as jk jars
        zip.putNextEntry(entry);
        try (var in = Files.newInputStream(file)) {
            in.transferTo((OutputStream) zip);
        }
        zip.closeEntry();
    }
}
