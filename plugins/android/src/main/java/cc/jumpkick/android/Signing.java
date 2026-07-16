// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.StepExec;
import com.android.apksig.ApkSigner;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Signing identities for the android packagers. Two shapes (android-plan §3.1):
 *
 * <ul>
 *   <li><b>Debug</b> — a keytool-generated {@code androiddebugkey} keystore (the standard
 *       identity), regenerated under the work dir. v1+v2.
 *   <li><b>Release</b> — the {@code [android.signing.<name>]} config the selected build type
 *       references: {@code signing.store-file}/{@code signing.key-alias} arrive as effective
 *       config; the passwords are SECRETS ({@code secret = true} sub-schema keys) riding the
 *       package spec's side channel — never config, never logs. v1+v2+v3 (rotation schema'd,
 *       deferred).
 * </ul>
 */
final class Signing {

    private Signing() {}

    /** A loaded signer identity + which signature schemes to apply. */
    record Identity(PrivateKey key, List<X509Certificate> certs, String name, boolean v3) {}

    /** True when the effective config carries a release signing reference. */
    static boolean hasReleaseConfig(PackageIo io) {
        return io.config().stringOpt("signing.store-file").isPresent();
    }

    /** The configured release identity ({@code [android.signing.<name>]}). */
    static Identity release(PackageIo io) throws Exception {
        Path storeFile = Path.of(io.config().string("signing.store-file"));
        if (!Files.isRegularFile(storeFile)) {
            throw new IllegalStateException("signing.store-file does not exist: " + storeFile);
        }
        String alias = io.config().string("signing.key-alias");
        char[] storePass = io.secret("signing.store-password").orElse("").toCharArray();
        char[] keyPass =
                io.secret("signing.key-password").map(String::toCharArray).orElse(storePass);
        KeyStore ks = KeyStore.getInstance(storeFile.toString().endsWith(".jks") ? "JKS" : "PKCS12");
        try (InputStream in = Files.newInputStream(storeFile)) {
            ks.load(in, storePass);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, keyPass);
        if (key == null) {
            throw new IllegalStateException("no key `" + alias + "` in " + storeFile.getFileName());
        }
        List<X509Certificate> certs = new ArrayList<>();
        for (var cert : ks.getCertificateChain(alias)) {
            certs.add((X509Certificate) cert);
        }
        return new Identity(key, certs, alias, true);
    }

    /** A fresh keytool-generated debug identity under {@code work}. */
    static Identity debug(PackageIo io, Path work) throws Exception {
        Path keystore = debugKeystore(io, work);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, "android".toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey("androiddebugkey", "android".toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (var cert : ks.getCertificateChain("androiddebugkey")) {
            certs.add((X509Certificate) cert);
        }
        return new Identity(key, certs, "androiddebugkey", false);
    }

    /** The generated debug keystore file itself (bundletool's build-apks wants the file). */
    static Path debugKeystore(PackageIo io, Path work) throws Exception {
        Path keystore = work.resolve("debug.keystore");
        if (Files.isRegularFile(keystore)) return keystore;
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
        return keystore;
    }

    /** apksig over {@code unsigned} → {@code out}: v1+v2 always, v3 for release identities. */
    static void sign(Identity identity, Path unsigned, Path out) throws Exception {
        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder(identity.name(), identity.key(), identity.certs()).build();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(unsigned.toFile())
                .setOutputApk(out.toFile())
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(identity.v3())
                .build()
                .sign();
    }
}
