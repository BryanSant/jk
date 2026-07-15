// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.config.SessionContext;
import build.jumpkick.config.JkBuildParser;
import build.jumpkick.model.JkBuild;
import build.jumpkick.resolver.ResolveObserver;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Step 4's ecosystem gate: a Kotlin Android app using <b>Room</b> (entity + dao +
 * database) and <b>Hilt</b> (@HiltAndroidApp / @AndroidEntryPoint) builds green through the KSP2
 * round — the real acceptance test for KSP support, per the plan ("Room + Hilt green … they are
 * the ecosystem's real acceptance test").
 *
 * <p>Hilt runs in its documented <em>plugin-less</em> mode: under Gradle, dagger.hilt's plugin
 * rewrites annotated classes' superclasses with a bytecode transform; without it, the annotation
 * takes the base class explicitly and the user class extends the generated {@code Hilt_*} — which
 * also exercises kotlinc resolving KSP-generated <em>Java</em> sources ({@code
 * -Xjava-source-roots} carries the generated dir).
 *
 * <p>Network test (Google Maven + Central; Room/Hilt closures are real); the CAS + SDK persist
 * under build/ so repeat runs are warm.
 */
class KspRoomHiltTest {

    @Test
    void room_and_hilt_generate_via_ksp_and_the_app_builds(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(build.jumpkick.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());

        writeProject(project);
        acceptLicenses();

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Pipeline lock = LockPipelines.lockPipeline(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        PipelineResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();
        assertThat(lockResult.success()).isTrue();

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

        // The KSP round ran: Room generated the database impl, Hilt generated components.
        Path kspOut = project.resolve("target/ksp");
        assertThat(anyFile(kspOut, "HelloDb_Impl")).as("Room database impl generated").isTrue();
        assertThat(anyFile(kspOut, "Hilt_HelloApp")).as("Hilt application base generated").isTrue();
        assertThat(anyFile(kspOut, "Hilt_MainActivity"))
                .as("Hilt activity base generated")
                .isTrue();

        // Generated sources compiled with the app (the unions fed both compilers).
        Path classes = project.resolve("target/classes");
        try (Stream<Path> walk = Files.walk(classes)) {
            assertThat(walk.map(p -> p.getFileName().toString()))
                    .as("generated classes compiled into the classes dir")
                    .anyMatch(n -> n.startsWith("HelloDb_Impl"))
                    .anyMatch(n -> n.startsWith("Hilt_MainActivity"));
        }

        // And the whole thing packaged: the debug APK exists (dex closure included the gen).
        assertThat(project.resolve("target/lib/roomhilt-1.0.0.apk")).exists();
    }

    private static boolean anyFile(Path root, String stem) throws Exception {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(p -> p.getFileName().toString().startsWith(stem));
        }
    }

    private static void acceptLicenses() throws Exception {
        var sdk = build.jumpkick.androidsdk.AndroidSdk.resolve();
        var installer = new build.jumpkick.androidsdk.AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-34")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(
                        license.getKey(), build.jumpkick.androidsdk.AndroidRepoFeed.licenseHash(license.getValue()));
            }
        }
    }

    private static void writeProject(Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                [project]
                name    = "roomhilt"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                kotlin  = "^2.4.0"
                layout  = "simple"

                [android]
                namespace   = "com.example.roomhilt"
                compile-sdk = 34
                min-sdk     = 24

                [dependencies]
                room-runtime = { group = "androidx.room", name = "room-runtime", version = "=2.7.2" }
                hilt-android = { group = "com.google.dagger", name = "hilt-android", version = "=2.60.1" }
                # Pinned pre-navigationevent: newer activity drags androidx's KMP-split compose
                # runtime-annotation, whose Maven POMs double-resolve (root AAR + -jvm variant) —
                # the Gradle-module-metadata fidelity gap recorded for Step 5.
                activity     = { group = "androidx.activity", name = "activity", version = "=1.9.3" }

                [processor-dependencies]
                room-compiler = { group = "androidx.room", name = "room-compiler", version = "=2.7.2" }
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
        Path src = Files.createDirectories(project.resolve("src/com/example/roomhilt"));
        Files.writeString(src.resolve("Greeting.kt"), """
                package com.example.roomhilt

                import androidx.room.Dao
                import androidx.room.Database
                import androidx.room.Entity
                import androidx.room.PrimaryKey
                import androidx.room.Query
                import androidx.room.RoomDatabase

                @Entity
                data class Greeting(@PrimaryKey val id: Long, val text: String)

                @Dao
                interface GreetingDao {
                    @Query("SELECT * FROM Greeting")
                    fun all(): List<Greeting>
                }

                @Database(entities = [Greeting::class], version = 1, exportSchema = false)
                abstract class HelloDb : RoomDatabase() {
                    abstract fun greetings(): GreetingDao
                }
                """);
        Files.writeString(src.resolve("HelloApp.kt"), """
                package com.example.roomhilt

                import android.app.Application
                import dagger.hilt.android.HiltAndroidApp

                // Plugin-less Hilt (no bytecode transform in jk — android-plan §3.5): the
                // annotation names the base explicitly and the class extends the generated one.
                @HiltAndroidApp(Application::class)
                class HelloApp : Hilt_HelloApp()
                """);
        Files.writeString(src.resolve("MainActivity.kt"), """
                package com.example.roomhilt

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import dagger.hilt.android.AndroidEntryPoint
                import javax.inject.Inject

                @AndroidEntryPoint(ComponentActivity::class)
                class MainActivity : Hilt_MainActivity() {

                    @Inject lateinit var repo: GreetingRepo

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        repo.describe()
                    }
                }
                """);
        Files.writeString(src.resolve("GreetingRepo.kt"), """
                package com.example.roomhilt

                import javax.inject.Inject

                class GreetingRepo @Inject constructor() {
                    fun describe(): String = "greetings"
                }
                """);
    }
}
