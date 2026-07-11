// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.PackageIo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The {@code apk} packager: assemble the unsigned APK from the linked resource package
 * ({@code resources.ap_} — compiled manifest + {@code resources.arsc} + {@code res/}) plus the
 * dex output, then debug-sign it v1+v2 with the plugin's bundled apksig against a generated
 * debug keystore ({@code keytool}, the standard {@code androiddebugkey} identity).
 *
 * <p>Spike-grade notes (android-plan Phase 1): no zipalign pass beyond preserving the stored
 * entries aapt2 laid out (16KB page-alignment matters only for bundled native libs, which a
 * hello-world has none of), and the debug keystore regenerates per package run rather than
 * persisting under a user dir.
 */
final class ApkPackager {

    private ApkPackager() {}

    static void produce(PackageIo io) throws Exception {
        Path resPackage = io.stepOutput("android-res")
                .map(dir -> dir.resolve("packaged").resolve("resources.ap_"))
                .filter(Files::isRegularFile)
                .orElseThrow(() -> new IllegalStateException("android-res produced no resources.ap_"));
        Path dexDir = dexOutput(io);

        Path out = io.artifactPath();
        Path work = Files.createTempDirectory("jk-apk-");
        Path unsigned = work.resolve("unsigned.apk");

        io.label("assemble " + out.getFileName());
        assemble(io, resPackage, dexDir, unsigned);

        if (Signing.hasReleaseConfig(io)) {
            io.label("sign (release)");
            Signing.sign(Signing.release(io), unsigned, out);
        } else {
            io.label("sign (debug)");
            Signing.sign(Signing.debug(io, work), unsigned, out);
        }
        AndroidDeps.copyRetraceArtifacts(io);
    }

    /** The dex step's output — {@code android-r8} on a minified build, else {@code android-dex}. */
    static Path dexOutput(PackageIo io) {
        return io.stepOutput("android-r8")
                .or(() -> io.stepOutput("android-dex"))
                .map(dir -> dir.resolve("dex"))
                .filter(Files::isDirectory)
                .orElseThrow(() -> new IllegalStateException("no dex output — did the dex/r8 step run?"));
    }

    /**
     * The unsigned APK: every entry of the aapt2-linked package copied with its compression
     * preserved ({@code resources.arsc} must stay STORED), each {@code classes*.dex}, the merged
     * {@code assets/} (module wins over AAR deps), and AAR native libs under {@code lib/<abi>/}
     * (STORED — apksig's output engine page-aligns uncompressed {@code .so} entries).
     */
    private static void assemble(PackageIo io, Path resPackage, Path dexDir, Path unsigned) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unsigned));
                ZipFile in = new ZipFile(resPackage.toFile())) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                write(zip, entry.getName(), bytes, entry.getMethod());
            }
            List<Path> dexFiles = ResourceStep.filesUnder(dexDir, ".dex");
            if (dexFiles.isEmpty()) {
                throw new IOException("no .dex files under " + dexDir);
            }
            for (Path dex : dexFiles) {
                write(zip, dex.getFileName().toString(), Files.readAllBytes(dex), ZipEntry.DEFLATED);
            }
            for (var asset : AndroidDeps.mergedAssets(io).entrySet()) {
                write(zip, "assets/" + asset.getKey(), Files.readAllBytes(asset.getValue()), ZipEntry.DEFLATED);
            }
            for (var lib : AndroidDeps.nativeLibs(io).entrySet()) {
                write(zip, "lib/" + lib.getKey(), Files.readAllBytes(lib.getValue()), ZipEntry.STORED);
            }
        }
    }

    private static void write(ZipOutputStream zip, String name, byte[] bytes, int method) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            entry.setSize(bytes.length);
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

}
