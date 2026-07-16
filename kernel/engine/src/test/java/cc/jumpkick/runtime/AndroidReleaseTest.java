// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Step 3 acceptance ("release"): {@code jk build --release} on an app module
 * produces a Play-uploadable AAB —
 *
 * <ul>
 *   <li>the variant machinery selects the release build type ({@code minify} defaults ON), the
 *       {@code android-r8} step replaces {@code android-dex}, and the {@code aab} packager
 *       replaces {@code apk} via {@code [[packaging.variant]]} on the injected {@code build-type};
 *   <li>R8 full mode actually shrinks: the unused class is gone ({@code usage.txt}), the launcher
 *       activity survives via aapt2's generated keep rules, and the retrace artifacts land at
 *       {@code target/r8/};
 *   <li>the release signing config resolves through {@code env:} indirection with the passwords
 *       riding the secrets side channel — the AAB verifies with {@code jarsigner -verify};
 *   <li>the AAB is structurally bundletool-shaped and {@code bundletool validate} +
 *       {@code build-apks --mode=universal} both accept it (the local-deploy path).
 * </ul>
 *
 * <p>Same harness as {@link AndroidWorkspaceTest}: real tools, persistent CAS/SDK under build/.
 */
class AndroidReleaseTest {

    @Test
    void release_build_produces_verified_aab(@TempDir Path tmp) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("relapp"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(cc.jumpkick.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());

        Path keystore = tmp.resolve("release.jks");
        generateKeystore(keystore);
        writeApp(app);
        acceptLicenses();

        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION, "test", Lockfile.RESOLUTION_ALGORITHM, null, null,
                        List.of(), List.of()),
                app.resolve("jk.lock"));
        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
                        app,
                        cache,
                        app.resolve("jk.toml"),
                        app.resolve("jk.lock"),
                        app,
                        1,
                        0,
                        null,
                        null,
                        true,
                        false,
                false,
                false,
                java.util.Set.of(),
                cc.jumpkick.config.SessionContext.current())
                .withVariant(
                        "release",
                        Map.of(
                                "RELEASE_KEYSTORE", keystore.toAbsolutePath().toString(),
                                "RELEASE_STORE_PASSWORD", "rel-store-pass",
                                "RELEASE_KEY_PASSWORD", "rel-key-pass"));
        Pipeline pipeline = BuildPipelines.coreBuilder(in).build();
        PipelineResult result = pipeline.run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        // The release steps ran: R8 (not d8) fed the aab packager.
        List<String> stepNames = new ArrayList<>();
        for (var step : pipeline.steps()) stepNames.add(step.name());
        assertThat(stepNames).contains("plugin-android-r8").doesNotContain("plugin-android-dex");

        // The artifact is the AAB, in bundletool's base-module layout.
        Path aab = app.resolve("target/lib/relapp-1.0.0.aab");
        assertThat(aab).exists();
        Set<String> entries = zipEntries(aab);
        assertThat(entries).contains(
                "base/manifest/AndroidManifest.xml", "base/resources.pb", "base/dex/classes.dex",
                "base/assets/greeting.txt");

        // R8 really shrank: retrace artifacts at target/r8/, the unused class stripped, the
        // launcher activity kept (aapt2's generated rules), the proguard-files keep honored.
        Path r8Dir = app.resolve("target/r8");
        assertThat(r8Dir.resolve("mapping.txt")).exists();
        assertThat(r8Dir.resolve("seeds.txt")).exists();
        assertThat(r8Dir.resolve("usage.txt")).exists();
        String usage = Files.readString(r8Dir.resolve("usage.txt"));
        assertThat(usage).contains("com.example.relapp.UnusedHelper");
        String seeds = Files.readString(r8Dir.resolve("seeds.txt"));
        assertThat(seeds).contains("com.example.relapp.MainActivity").contains("com.example.relapp.KeptByRule");

        // The signature verifies — the env-indirected release identity signed it.
        Path jarsigner = Path.of(System.getProperty("java.home"), "bin", "jarsigner");
        var verify = new ProcessBuilder(
                        jarsigner.toString(), "-verify", aab.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();
        String verifyOut = new String(verify.getInputStream().readAllBytes());
        assertThat(verify.waitFor()).as(verifyOut).isZero();
        assertThat(verifyOut).contains("jar verified");

        // bundletool accepts the bundle: validate + the universal-APK local-deploy path.
        Path bundletool = cache.resolve("plugin-tools/com.android.tools.build_bundletool_1.17.2");
        assertThat(bundletool).isDirectory();
        assertThat(bundletoolRun(bundletool, "validate", "--bundle=" + aab.toAbsolutePath()))
                .contains("App Bundle information");
        Path apks = tmp.resolve("universal.apks");
        Path aapt2 = app.resolve("target/plugin/android-res/tools/aapt2");
        assertThat(aapt2).exists();
        bundletoolRun(
                bundletool,
                "build-apks",
                "--bundle=" + aab.toAbsolutePath(),
                "--output=" + apks.toAbsolutePath(),
                "--mode=universal",
                "--aapt2=" + aapt2.toAbsolutePath());
        assertThat(zipEntries(apks)).contains("universal.apk");
    }

    /** Fork bundletool over its fetched closure; returns its output, asserting exit 0. */
    private static String bundletoolRun(Path closureDir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        StringBuilder cp = new StringBuilder();
        try (var listing = Files.list(closureDir)) {
            for (Path jar : (Iterable<Path>) listing.sorted()::iterator) {
                if (!jar.toString().endsWith(".jar")) continue;
                if (cp.length() > 0) cp.append(java.io.File.pathSeparatorChar);
                cp.append(jar.toAbsolutePath());
            }
        }
        command.add(cp.toString());
        command.add("com.android.tools.build.bundletool.BundleToolMain");
        command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    private static void generateKeystore(Path keystore) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        var process = new ProcessBuilder(
                        keytool.toString(),
                        "-genkeypair",
                        "-keystore",
                        keystore.toAbsolutePath().toString(),
                        "-storetype",
                        "JKS",
                        "-storepass",
                        "rel-store-pass",
                        "-keypass",
                        "rel-key-pass",
                        "-alias",
                        "upload",
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "2048",
                        "-validity",
                        "10000",
                        "-dname",
                        "CN=Release Test,O=jk,C=US")
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }

    private static void acceptLicenses() throws Exception {
        var sdk = cc.jumpkick.androidsdk.AndroidSdk.resolve();
        var installer = new cc.jumpkick.androidsdk.AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-28")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(
                        license.getKey(), cc.jumpkick.androidsdk.AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }
    }

    private static Set<String> zipEntries(Path zipFile) throws Exception {
        Set<String> entries = new HashSet<>();
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            zip.stream().forEach(e -> entries.add(e.getName()));
        }
        return entries;
    }

    private static void writeApp(Path app) throws Exception {
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                name    = "relapp"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [android]
                namespace   = "com.example.relapp"
                compile-sdk = 28
                min-sdk     = 24

                [variants.build-type.release.android]
                proguard-files = ["proguard-rules.pro"]
                signing        = "release"

                [android.signing.release]
                store-file     = "env:RELEASE_KEYSTORE"
                store-password = "env:RELEASE_STORE_PASSWORD"
                key-alias      = "upload"
                key-password   = "env:RELEASE_KEY_PASSWORD"

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);
        Files.writeString(app.resolve("proguard-rules.pro"), """
                # the app's own rules — R8 must honor declared proguard-files
                -keep class com.example.relapp.KeptByRule { *; }
                """);
        Files.writeString(app.resolve("AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:label="@string/app_name">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        Files.createDirectories(app.resolve("res/values"));
        Files.writeString(app.resolve("res/values/strings.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">Release App</string>
                </resources>
                """);
        Files.createDirectories(app.resolve("assets"));
        Files.writeString(app.resolve("assets/greeting.txt"), "hello from assets\n");
        Path src = Files.createDirectories(app.resolve("src/com/example/relapp"));
        Files.writeString(src.resolve("MainActivity.java"), """
                package com.example.relapp;

                import android.app.Activity;
                import android.os.Bundle;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.main);
                    }
                }
                """);
        Files.writeString(src.resolve("UnusedHelper.java"), """
                package com.example.relapp;

                /** Never referenced — R8 full mode must strip it (usage.txt records the removal). */
                public final class UnusedHelper {
                    private UnusedHelper() {}

                    public static String unused() {
                        return "never called";
                    }
                }
                """);
        Files.writeString(src.resolve("KeptByRule.java"), """
                package com.example.relapp;

                /** Unreferenced too — but the app's proguard-rules.pro keeps it. */
                public final class KeptByRule {
                    public String marker() {
                        return "kept";
                    }
                }
                """);
        Files.createDirectories(app.resolve("res/layout"));
        Files.writeString(app.resolve("res/layout/main.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:text="@string/app_name"/>
                """);
    }
}
