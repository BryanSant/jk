// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.cache.Cas;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileWriter;
import dev.jkbuild.model.Scope;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The P6 validation gate (build-plugins plan §4; android-plan.md Phase 1): {@code jk build} on a
 * minimal Android hello-world produces a signed APK via {@code plugins/android}, with the
 * R-gen → compile → dex → assemble chain running entirely over the public SPI — the engine has
 * zero Android-specific code.
 *
 * <p>Real tools, really fetched: aapt2 (per-OS classifier) and r8 from Google Maven, the
 * Phase-1 platform stand-in (Maven-published android-all — see the plugin manifest's note) from
 * Central. The CAS lives under the module's build dir, not a @TempDir, so repeat runs are warm
 * (the platform jar is ~115MB once).
 */
class AndroidSpikeTest {


    @Test
    void hello_world_apk_via_the_android_plugin(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("hello"));
        // A persistent CAS + SDK root across runs — the platform is a one-time download.
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(dev.jkbuild.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());

        writeProject(project);

        // Accept the SDK licenses exactly as `jk android licenses --yes` does — the installer
        // refuses to download otherwise (the gate the AndroidSdkTest covers in isolation).
        var sdk = dev.jkbuild.androidsdk.AndroidSdk.resolve();
        var installer = new dev.jkbuild.androidsdk.AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-28")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(
                        license.getKey(), dev.jkbuild.androidsdk.AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }

        Cas cas = new Cas(cache);
        // The platform is PROVIDED via the manifest's [[contribute.provided-classpath]] — the
        // lock carries no platform artifact at all.
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION, "test", Lockfile.RESOLUTION_ALGORITHM, null, null,
                        List.of(), List.of()),
                project.resolve("jk.lock"));

        // The real declared pipeline, exactly as jk build assembles it.
        BuildPipeline.Inputs in = new BuildPipeline.Inputs(
                project,
                cache,
                project.resolve("jk.toml"),
                project.resolve("jk.lock"),
                project,
                1,
                0,
                null,
                null,
                true,
                false);
        Goal goal = BuildPipeline.coreBuilder(in).build();

        assertThat(goal.phases().stream().map(p -> p.name()))
                .contains("plugin-android-manifest", "plugin-android-res", "plugin-android-dex", "package-jar");

        GoalResult result = goal.run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        // manifest-merger ran: package + <uses-sdk> injected into the merged manifest.
        Path merged = project.resolve("target/plugin/android-manifest/merged/AndroidManifest.xml");
        assertThat(merged).exists();
        assertThat(Files.readString(merged))
                .contains("package=\"com.example.hello\"")
                .contains("android:minSdkVersion=\"24\"");

        // R.java generated (before compile) and contributed to the source set.
        Path rJava = project.resolve("target/plugin/android-res/gen/com/example/hello/R.java");
        assertThat(rJava).exists();
        assertThat(Files.readString(rJava)).contains("public static final int main");

        // R compiled with the app (the contributed source reached javac) — find it wherever the
        // layout put the classes dir.
        try (var walk = Files.walk(project.resolve("target"))) {
            assertThat(walk.filter(f -> f.getFileName().toString().equals("R.class")).toList())
                    .isNotEmpty();
        }

        // Dex produced from the compiled classes.
        assertThat(project.resolve("target/plugin/android-dex/dex/classes.dex")).exists();

        // The APK replaced the main artifact under its own extension, assembled + debug-signed.
        Path apk = project.resolve("target/lib/hello-1.0.0.apk");
        assertThat(apk).exists();
        Set<String> entries = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            zip.stream().forEach(e -> entries.add(e.getName()));
        }
        assertThat(entries)
                .contains("AndroidManifest.xml", "resources.arsc", "classes.dex", "res/layout/main.xml")
                .anyMatch(name -> name.startsWith("META-INF/") && name.endsWith(".RSA")) // v1
                .contains("META-INF/MANIFEST.MF");
        // v2 signing leaves no entry — verify with the same apksig the plugin signs with.
        assertThat(verifiedByApksig(apk)).isTrue();

        // ---- jk run onto a device: the declared deploy verb, against a scripted fake adb ----
        // (a live `adb devices` run is the honest remaining gap — no device in CI).
        Path adbLog = tmp.resolve("adb.log");
        Path fakeAdb = tmp.resolve("fake-adb");
        Files.writeString(fakeAdb, "#!/bin/sh\necho \"$@\" >> " + adbLog.toAbsolutePath() + "\necho Success\n");
        fakeAdb.toFile().setExecutable(true);

        var deploy = PluginVerbs.run(project, cache, "deploy", List.of("--adb", fakeAdb.toAbsolutePath().toString()));
        assertThat(deploy.error()).isNull();
        assertThat(deploy.found()).isTrue();
        assertThat(deploy.exit()).isZero();
        String adbCalls = Files.readString(adbLog);
        assertThat(adbCalls)
                .contains("install -r " + apk.toAbsolutePath())
                .contains("shell am start -n com.example.hello/com.example.hello.MainActivity");

        // ---- instrumented tests: the instrument verb against a scripted transcript ----
        // (a live device run remains gated — the parser and adb argv are the testable surface).
        Path instrLog = tmp.resolve("instr-adb.log");
        Path instrAdb = tmp.resolve("fake-instr-adb");
        Files.writeString(instrAdb, "#!/bin/sh\n"
                + "echo \"$@\" >> " + instrLog.toAbsolutePath() + "\n"
                + "case \"$*\" in\n"
                + "  *\"am instrument\"*)\n"
                + "    printf '%s\\n' \\\n"
                + "      'INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest' \\\n"
                + "      'INSTRUMENTATION_STATUS: test=works' \\\n"
                + "      'INSTRUMENTATION_STATUS_CODE: 1' \\\n"
                + "      'INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest' \\\n"
                + "      'INSTRUMENTATION_STATUS: test=works' \\\n"
                + "      'INSTRUMENTATION_STATUS_CODE: 0' \\\n"
                + "      'INSTRUMENTATION_STATUS: class=com.example.hello.SmokeTest' \\\n"
                + "      'INSTRUMENTATION_STATUS: test=broken' \\\n"
                + "      'INSTRUMENTATION_STATUS_CODE: 1' \\\n"
                + "      'INSTRUMENTATION_STATUS: test=broken' \\\n"
                + "      'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: boom' \\\n"
                + "      'INSTRUMENTATION_STATUS_CODE: -2' \\\n"
                + "      'INSTRUMENTATION_CODE: -1' ;;\n"
                + "  *) echo Success ;;\n"
                + "esac\n");
        instrAdb.toFile().setExecutable(true);
        var instrument =
                PluginVerbs.run(project, cache, "instrument", List.of("--adb", instrAdb.toAbsolutePath().toString()));
        assertThat(instrument.error()).isNull();
        assertThat(instrument.found()).isTrue();
        assertThat(instrument.exit()).isEqualTo(1); // the transcript carries one failure
        String instrOut = String.join("\n", instrument.output());
        assertThat(instrOut)
                .contains("✓ com.example.hello.SmokeTest.works")
                .contains("✗ com.example.hello.SmokeTest.broken FAILED")
                .contains("1 passed, 1 failed");
        assertThat(Files.readString(instrLog))
                .contains("install -r " + apk.toAbsolutePath())
                .contains("shell am instrument -r -w com.example.hello/androidx.test.runner.AndroidJUnitRunner");

        // ---- managed devices: jk avd create/list against the managed SDK root ----
        Path fakeImage = sdkRoot.resolve("system-images/android-28/default/x86_64");
        Files.createDirectories(fakeImage);
        var avdCreate = PluginVerbs.run(
                project, cache, "avd",
                List.of("create", "spike", "--system-image", "system-images;android-28;default;x86_64"));
        assertThat(avdCreate.error()).isNull();
        assertThat(avdCreate.exit()).isZero();
        Path avdConfig = sdkRoot.resolve("avd/spike.avd/config.ini");
        assertThat(avdConfig).exists();
        assertThat(Files.readString(avdConfig))
                .contains("image.sysdir.1=system-images/android-28/default/x86_64/")
                .contains("tag.id=default");
        var avdList = PluginVerbs.run(project, cache, "avd", List.of("list"));
        assertThat(String.join("\n", avdList.output())).contains("spike");
        // boot: refuses gracefully without the emulator component (no ~300MB download in CI).
        var avdBoot = PluginVerbs.run(project, cache, "avd", List.of("boot", "spike"));
        assertThat(avdBoot.exit()).isEqualTo(1);
        assertThat(String.join("\n", avdBoot.output())).contains("emulator component is not installed");

        // ---- the provisioning surface: component status over the same verb machinery ----
        var status = PluginVerbs.run(project, cache, "android", List.of("sdk"));
        assertThat(status.error()).isNull();
        assertThat(status.exit()).isZero();
        assertThat(String.join("\n", status.output()))
                .contains("platforms;android-28: installed");
    }

    /** apksig's verifier from the fetched CAS jar — proves v1+v2 without an emulator. */
    private static boolean verifiedByApksig(Path apk) throws Exception {
        Path jar = Path.of(System.getProperty("jk.android.worker.jar"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()})) {
            Class<?> builderClass = loader.loadClass("com.android.apksig.ApkVerifier$Builder");
            Object builder = builderClass
                    .getConstructor(java.io.File.class)
                    .newInstance(apk.toFile());
            Object verifier = builderClass.getMethod("build").invoke(builder);
            Object apkResult = verifier.getClass().getMethod("verify").invoke(verifier);
            return (boolean) apkResult.getClass().getMethod("isVerified").invoke(apkResult);
        }
    }

    private static void writeProject(Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "hello"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [android]
                namespace   = "com.example.hello"
                compile-sdk = 28
                min-sdk     = 24

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);
        Files.writeString(project.resolve("AndroidManifest.xml"), """
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
        Path res = Files.createDirectories(project.resolve("res"));
        Files.createDirectories(res.resolve("values"));
        Files.writeString(res.resolve("values/strings.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">Hello jk</string>
                    <string name="hello">Hello from jk!</string>
                </resources>
                """);
        Files.createDirectories(res.resolve("layout"));
        Files.writeString(res.resolve("layout/main.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:gravity="center"
                    android:text="@string/hello"/>
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/hello"));
        Files.writeString(src.resolve("MainActivity.java"), """
                package com.example.hello;

                import android.app.Activity;
                import android.os.Bundle;

                /** The Compose-less hello world (android-plan Phase 1's exit shape). */
                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.main);
                    }
                }
                """);
    }
}
