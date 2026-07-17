// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineResult;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Step 5, blocker 4: Hilt unmodified-source parity. Sources spell plain
 * {@code @HiltAndroidApp class App : Application()} / {@code @AndroidEntryPoint class
 * MainActivity : ComponentActivity()} — no {@code Hilt_*} in source, exactly what an AGP project
 * writes. {@code [android] hilt = true} contributes the processor's superclass-validation toggle
 * (KSP {@code -processor-options}) and registers the {@code android-hilt-transform} step, whose
 * output REPLACES the classes dir (the generic classes-transform SPI); dex consumes the rewritten
 * hierarchy. The transformed superclass is asserted byte-for-byte via the class-file API.
 *
 * <p>Network test (Google Maven + Central); the CAS + SDK persist under build/ so repeat runs
 * are warm (same shared cache as KspRoomHiltTest).
 */
class HiltTransformTest {

    @Test
    void unmodified_hilt_sources_build_and_superclasses_rewrite(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(cc.jumpkick.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());

        writeProject(project);
        acceptLicenses();

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Pipeline lock = LockPipelines.lockPipeline(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        PipelineResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();

        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
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
                false,
                false,
                false,
                java.util.Set.of(),
                SessionContext.current());
        PipelineResult result = BuildPipelines.coreBuilder(in).build().run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();

        // KSP generated the bases from the UNMODIFIED sources (validation toggle worked).
        assertThat(anyFile(project.resolve("target/ksp"), "Hilt_MainActivity"))
                .as("Hilt activity base generated without the plugin-less spelling")
                .isTrue();

        // The transform replaced the classes dir: MainActivity now extends Hilt_MainActivity.
        Path transformed = project.resolve("target/plugin/android-hilt-transform/classes");
        assertThat(superOf(transformed.resolve("com/example/hilttx/MainActivity.class")))
                .isEqualTo("com/example/hilttx/Hilt_MainActivity");
        assertThat(superOf(transformed.resolve("com/example/hilttx/HelloApp.class")))
                .isEqualTo("com/example/hilttx/Hilt_HelloApp");
        // Untouched classes copied through (the transform output IS the classes dir).
        assertThat(transformed.resolve("com/example/hilttx/GreetingRepo.class")).exists();

        // Receiver-shaped entry point: superclass swapped AND super.onReceive(context, intent)
        // injected at the start of the override (the generated base's onReceive is the injector).
        Path receiver = transformed.resolve("com/example/hilttx/PingReceiver.class");
        assertThat(superOf(receiver)).isEqualTo("com/example/hilttx/Hilt_PingReceiver");
        assertThat(callsSuperOnReceive(receiver, "com/example/hilttx/Hilt_PingReceiver"))
                .as("super.onReceive injected into the receiver's override")
                .isTrue();

        // The compiler's own output kept the natural hierarchy — only the replacement rewrote.
        assertThat(superOf(findClass(project.resolve("target"), "classes", "MainActivity.class")))
                .isNotEqualTo("com/example/hilttx/Hilt_MainActivity");

        // And the whole thing packaged (dex ran over the transformed dir).
        assertThat(project.resolve("target/lib/hilttx-1.0.0.apk")).exists();
    }

    private static String superOf(Path classFile) throws Exception {
        return ClassFile.of()
                .parse(Files.readAllBytes(classFile))
                .superclass()
                .orElseThrow()
                .asInternalName();
    }

    /** Does the class's {@code onReceive} invokespecial {@code owner.onReceive}? */
    private static boolean callsSuperOnReceive(Path classFile, String owner) throws Exception {
        return ClassFile.of().parse(Files.readAllBytes(classFile)).methods().stream()
                .filter(m -> m.methodName().equalsString("onReceive"))
                .flatMap(m -> m.code().stream())
                .flatMap(java.lang.classfile.CodeModel::elementStream)
                .anyMatch(el -> el instanceof java.lang.classfile.instruction.InvokeInstruction inv
                        && inv.opcode() == java.lang.classfile.Opcode.INVOKESPECIAL
                        && inv.owner().asInternalName().equals(owner)
                        && inv.name().equalsString("onReceive"));
    }

    /** The first {@code name} match anywhere under {@code root/subdir} (the compiler's own output). */
    private static Path findClass(Path root, String subdir, String name) throws Exception {
        try (var walk = Files.walk(root.resolve(subdir))) {
            return walk.filter(p -> p.getFileName().toString().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " not under " + root.resolve(subdir)));
        }
    }

    private static boolean anyFile(Path root, String stem) throws Exception {
        if (!Files.isDirectory(root)) return false;
        try (var walk = Files.walk(root)) {
            return walk.anyMatch(p -> p.getFileName().toString().startsWith(stem));
        }
    }

    private static void acceptLicenses() throws Exception {
        var sdk = cc.jumpkick.androidsdk.AndroidSdk.resolve();
        var installer = new cc.jumpkick.androidsdk.AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-34")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(
                        license.getKey(), cc.jumpkick.androidsdk.AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }
    }

    private static void writeProject(Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "hilttx"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                kotlin  = "^2.4.0"
                layout  = "simple"

                [android]
                namespace   = "com.example.hilttx"
                compile-sdk = 34
                min-sdk     = 24
                hilt        = true

                [dependencies]
                hilt-android = { group = "com.google.dagger", name = "hilt-android", version = "=2.60.1" }
                # Pinned pre-navigationevent, same as KspRoomHiltTest.
                activity     = { group = "androidx.activity", name = "activity", version = "=1.9.3" }

                [processor-dependencies]
                hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version = "=2.60.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);
        Files.writeString(project.resolve("AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application android:name=".HelloApp">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/hilttx"));
        Files.writeString(src.resolve("HelloApp.kt"), """
                package com.example.hilttx

                import android.app.Application
                import dagger.hilt.android.HiltAndroidApp

                // Unmodified-source mode: no Hilt_* anywhere — the transform rewrites the super.
                @HiltAndroidApp
                class HelloApp : Application()
                """);
        Files.writeString(src.resolve("MainActivity.kt"), """
                package com.example.hilttx

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import dagger.hilt.android.AndroidEntryPoint
                import javax.inject.Inject

                @AndroidEntryPoint
                class MainActivity : ComponentActivity() {

                    @Inject lateinit var repo: GreetingRepo

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        repo.describe()
                    }
                }
                """);
        Files.writeString(src.resolve("PingReceiver.kt"), """
                package com.example.hilttx

                import android.content.BroadcastReceiver
                import android.content.Context
                import android.content.Intent
                import dagger.hilt.android.AndroidEntryPoint
                import javax.inject.Inject

                @AndroidEntryPoint
                class PingReceiver : BroadcastReceiver() {

                    @Inject lateinit var repo: GreetingRepo

                    override fun onReceive(context: Context, intent: Intent) {
                        repo.describe()
                    }
                }
                """);
        Files.writeString(src.resolve("GreetingRepo.kt"), """
                package com.example.hilttx

                import javax.inject.Inject

                class GreetingRepo @Inject constructor() {
                    fun describe(): String = "greetings"
                }
                """);
    }
}
