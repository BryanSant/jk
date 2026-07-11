// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import dev.jkbuild.plugin.build.PackageIo;
import dev.jkbuild.plugin.build.StepExec;
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
 * The {@code aab} packager — the release default for app modules (android-plan §3.3): lay the
 * base module out the way bundletool wants it (proto manifest under {@code manifest/}, proto
 * resource table + {@code res/} from aapt2's {@code --proto-format} link, dex under {@code dex/},
 * merged assets, AAR native libs under {@code lib/}), run {@code bundletool build-bundle}, then
 * jarsign the bundle (an AAB is a signed jar — apksig schemes are APK-only; Play re-signs the
 * APKs it derives). The R8 retrace artifacts copy to {@code target/r8/} exactly like the APK
 * path.
 */
final class AabPackager {

    private AabPackager() {}

    static void produce(PackageIo io) throws Exception {
        Path protoPackage = io.stepOutput("android-res")
                .map(dir -> dir.resolve("packaged").resolve("resources-proto.ap_"))
                .filter(Files::isRegularFile)
                .orElseThrow(() -> new IllegalStateException(
                        "android-res produced no proto resource package — release links twice"));
        Path dexDir = ApkPackager.dexOutput(io);
        Path bundletool = io.extra("bundletool")
                .orElseThrow(() -> new IllegalStateException("bundletool tool artifact not provided"));

        Path out = io.artifactPath();
        Path work = Files.createTempDirectory("jk-aab-");

        io.label("assemble base module");
        Path baseZip = work.resolve("base.zip");
        assembleBase(io, protoPackage, dexDir, baseZip);

        io.label("bundletool build-bundle");
        Path unsigned = work.resolve("unsigned.aab");
        StepExec.ToolRun.Result bundle = io.java()
                .classpath(ManifestStep.jarsIn(bundletool))
                .mainClass("com.android.tools.build.bundletool.BundleToolMain")
                .arg("build-bundle")
                .arg("--modules=" + baseZip.toAbsolutePath())
                .arg("--output=" + unsigned.toAbsolutePath())
                .run();
        if (bundle.exit() != 0) {
            throw new IllegalStateException("bundletool build-bundle failed:\n" + bundle.output());
        }

        io.label("sign bundle");
        signBundle(io, work, unsigned, out);
        AndroidDeps.copyRetraceArtifacts(io);
    }

    /**
     * The base-module zip in bundletool's layout: {@code manifest/AndroidManifest.xml} (proto),
     * {@code resources.pb}, {@code res/**}, {@code dex/classes*.dex}, {@code assets/**},
     * {@code lib/<abi>/*.so}, everything else from the proto link under {@code root/}.
     */
    private static void assembleBase(PackageIo io, Path protoPackage, Path dexDir, Path baseZip) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(baseZip));
                ZipFile in = new ZipFile(protoPackage.toFile())) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] bytes;
                try (InputStream stream = in.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                String name = entry.getName();
                String target;
                if (name.equals("AndroidManifest.xml")) {
                    target = "manifest/AndroidManifest.xml";
                } else if (name.equals("resources.pb") || name.startsWith("res/")) {
                    target = name;
                } else {
                    target = "root/" + name;
                }
                write(zip, target, bytes, entry.getMethod());
            }
            List<Path> dexFiles = ResourceStep.filesUnder(dexDir, ".dex");
            if (dexFiles.isEmpty()) {
                throw new IOException("no .dex files under " + dexDir);
            }
            for (Path dex : dexFiles) {
                write(zip, "dex/" + dex.getFileName(), Files.readAllBytes(dex), ZipEntry.DEFLATED);
            }
            for (var asset : AndroidDeps.mergedAssets(io).entrySet()) {
                write(zip, "assets/" + asset.getKey(), Files.readAllBytes(asset.getValue()), ZipEntry.DEFLATED);
            }
            for (var lib : AndroidDeps.nativeLibs(io).entrySet()) {
                write(zip, "lib/" + lib.getKey(), Files.readAllBytes(lib.getValue()), ZipEntry.DEFLATED);
            }
        }
    }

    /** jarsigner over the bundle: the release identity when configured, else the debug keystore. */
    private static void signBundle(PackageIo io, Path work, Path unsigned, Path out) throws Exception {
        Path keystore;
        String storePass;
        String keyPass;
        String alias;
        if (Signing.hasReleaseConfig(io)) {
            keystore = Path.of(io.config().string("signing.store-file"));
            alias = io.config().string("signing.key-alias");
            storePass = io.secret("signing.store-password").orElse("");
            keyPass = io.secret("signing.key-password").orElse(storePass);
        } else {
            keystore = Signing.debugKeystore(io, work);
            alias = "androiddebugkey";
            storePass = "android";
            keyPass = "android";
        }
        Files.copy(unsigned, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        StepExec.ToolRun.Result signed = io.tool("jarsigner")
                .arg("-keystore")
                .arg(keystore.toAbsolutePath().toString())
                .arg("-storepass")
                .arg(storePass)
                .arg("-keypass")
                .arg(keyPass)
                .arg(out.toAbsolutePath().toString())
                .arg(alias)
                .run();
        if (signed.exit() != 0) {
            // NEVER echo jarsigner's command line (it would carry the passwords) — its
            // stdout/stderr alone.
            throw new IllegalStateException("jarsigner failed:\n" + signed.output());
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
