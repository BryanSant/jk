// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.CacheSync;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.Pipeline;
import cc.jumpkick.run.PipelineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Step 2 acceptance, remote half: a real androidx dependency — published as an AAR —
 * resolves through the ordinary lock pipeline (the effective POM's {@code packaging} decides the
 * fetch extension; the lock's {@code path} records it), materializes as an exploded container, and
 * flows into compile (classes.jar), the app link (its {@code R.txt} regenerates a final-id
 * {@code R} under its namespace), and the dex closure.
 *
 * <p>Network test against Google Maven; the CAS persists under build/ so repeat runs are warm.
 */
class AndroidRemoteAarTest {

    @Test
    void androidx_aar_resolves_explodes_and_builds(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(cc.jumpkick.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());

        writeProject(project);
        acceptLicenses();

        // ---- 1. jk lock: packaging-aware resolution ----
        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Pipeline lock = LockPipelines.lockPipeline(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        PipelineResult lockResult = lock.run();
        assertThat(lockResult.errors()).isEmpty();
        assertThat(lockResult.success()).isTrue();

        var lockfile = LockfileReader.read(project.resolve("jk.lock"));
        var annotationAar = lockfile.artifacts().stream()
                .filter(a -> a.name().equals("androidx.core:core"))
                .findFirst()
                .orElseThrow();
        assertThat(annotationAar.isAar()).isTrue();
        assertThat(annotationAar.path()).endsWith(".aar");
        assertThat(annotationAar.checksum()).startsWith("sha256:");
        // SDK component pins recorded ([[sdk]] — the platform this project compiles against).
        assertThat(lockfile.sdk()).anyMatch(e -> e.component().equals("platforms;android-34"));

        // ---- 2. jk sync: the AAR re-fetches under its real extension ----
        var sync = new CacheSync(new cc.jumpkick.cache.Cas(cache), new cc.jumpkick.http.Http()).sync(lockfile);
        assertThat(sync.errors()).isEmpty();

        // ---- 3. jk build: compile against classes.jar, R from R.txt, dex the closure ----
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

        // androidx.core ships res + R.txt: its R regenerated under its namespace with final ids.
        Path depR = project.resolve("target/plugin/android-res/gen/androidx/core/R.java");
        assertThat(depR).exists();
        assertThat(Files.readString(depR)).contains("package androidx.core;").contains("0x7f");

        // The APK exists — compile (ContextCompat reference) and the dexed closure both held.
        assertThat(project.resolve("target/lib/remote-1.0.0.apk")).exists();
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
                name    = "remote"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [android]
                namespace   = "com.example.remote"
                compile-sdk = 34
                min-sdk     = 24

                [dependencies]
                core = { group = "androidx.core", name = "core", version = "=1.13.1" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);
        Files.writeString(project.resolve("AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN"/>
                                <category android:name="android.intent.category.LAUNCHER"/>
                            </intent-filter>
                        </activity>
                    </application>
                </manifest>
                """);
        Path src = Files.createDirectories(project.resolve("src/com/example/remote"));
        Files.writeString(src.resolve("MainActivity.java"), """
                package com.example.remote;

                import android.app.Activity;
                import android.os.Bundle;
                import androidx.core.content.ContextCompat;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        int color = ContextCompat.getColor(this, android.R.color.black);
                    }
                }
                """);
    }
}
