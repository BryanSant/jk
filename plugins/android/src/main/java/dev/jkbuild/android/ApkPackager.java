// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.android;

import com.android.apksig.ApkSigner;
import dev.jkbuild.plugin.build.PackageIo;
import dev.jkbuild.plugin.build.StepExec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
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
        Path dexDir = io.stepOutput("android-dex")
                .map(dir -> dir.resolve("dex"))
                .filter(Files::isDirectory)
                .orElseThrow(() -> new IllegalStateException("android-dex produced no dex output"));

        Path out = io.artifactPath();
        Path work = Files.createTempDirectory("jk-apk-");
        Path unsigned = work.resolve("unsigned.apk");

        io.label("assemble " + out.getFileName());
        assemble(resPackage, dexDir, unsigned);

        io.label("sign (debug)");
        sign(io, work, unsigned, out);
    }

    /**
     * The unsigned APK: every entry of the aapt2-linked package copied with its compression
     * preserved ({@code resources.arsc} must stay STORED), plus each {@code classes*.dex}.
     */
    private static void assemble(Path resPackage, Path dexDir, Path unsigned) throws IOException {
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

    /** Generate the debug keystore with keytool, then apksig v1+v2 sign to the artifact path. */
    private static void sign(PackageIo io, Path work, Path unsigned, Path out) throws Exception {
        Path keystore = work.resolve("debug.keystore");
        StepExec.ToolRun.Result keygen = io.tool("keytool")
                .arg("-genkeypair")
                .arg("-keystore")
                .arg(keystore.toAbsolutePath().toString())
                .arg("-storepass")
                .arg("android")
                .arg("-keypass")
                .arg("android")
                .arg("-alias")
                .arg("androiddebugkey")
                .arg("-keyalg")
                .arg("RSA")
                .arg("-keysize")
                .arg("2048")
                .arg("-validity")
                .arg("10000")
                .arg("-dname")
                .arg("CN=Android Debug,O=Android,C=US")
                .run();
        if (keygen.exit() != 0) {
            throw new IllegalStateException("keytool failed:\n" + keygen.output());
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, "android".toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("androiddebugkey", "android".toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (var cert : ks.getCertificateChain("androiddebugkey")) {
            certs.add((X509Certificate) cert);
        }
        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder("androiddebugkey", key, certs).build();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(unsigned.toFile())
                .setOutputApk(out.toFile())
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign();
    }
}
