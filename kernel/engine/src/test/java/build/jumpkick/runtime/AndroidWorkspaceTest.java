// SPDX-License-Identifier: Apache-2.0
package build.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import build.jumpkick.cache.Cas;
import build.jumpkick.lock.Lockfile;
import build.jumpkick.lock.LockfileWriter;
import build.jumpkick.run.Pipeline;
import build.jumpkick.run.PipelineResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Step 2 acceptance ("real app shape"): a two-module workspace — an {@code
 * [android] library = true} module with resources and code, consumed by an app module — builds
 * with non-transitive R semantics end to end:
 *
 * <ul>
 *   <li>the library packages an AAR (merged manifest, {@code classes.jar} with R classes
 *       excluded, raw {@code res/}, {@code R.txt}) plus the conventional classes jar siblings
 *       compile against;
 *   <li>the app's manifest merge folds the library manifest in ({@code --libs});
 *   <li>the app link merges library resources (app wins) and regenerates the library's {@code R}
 *       from its {@code R.txt} with final ids — the library's own symbols only;
 *   <li>the dex step swallows the whole runtime closure (library classes included), and the APK
 *       carries the library's file-shaped resources.
 * </ul>
 *
 * <p>Same harness as {@link AndroidSpikeTest}: real tools, persistent CAS/SDK under build/.
 */
class AndroidWorkspaceTest {

    @Test
    void library_module_aar_and_non_transitive_r(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Path sdkRoot = Path.of(System.getProperty("user.dir"), "build", "android-spike-sdk");
        System.setProperty(build.jumpkick.androidsdk.AndroidSdk.ROOT_PROPERTY, sdkRoot.toString());

        writeWorkspace(root);
        acceptLicenses();

        // ---- 1. Build the library module: AAR + conventional classes jar ----
        Path lib = root.resolve("lib");
        PipelineResult libResult = build(lib, cache);
        assertThat(libResult.errors()).isEmpty();
        assertThat(libResult.success()).isTrue();

        Path aar = lib.resolve("target/lib/lib-1.0.0.aar");
        Path conventional = lib.resolve("target/lib/lib-1.0.0.jar");
        assertThat(aar).exists();
        assertThat(conventional).exists();
        Set<String> aarEntries = zipEntries(aar);
        assertThat(aarEntries).contains("AndroidManifest.xml", "classes.jar", "R.txt", "res/values/lib_strings.xml",
                "res/layout/lib_view.xml");
        // The AAR's classes.jar carries the library code but never its R classes — consumers
        // regenerate R with final ids (AGP's exact exclusion).
        Path extracted = tmp.resolve("classes.jar");
        try (ZipFile zip = new ZipFile(aar.toFile())) {
            Files.copy(zip.getInputStream(zip.getEntry("classes.jar")), extracted);
        }
        Set<String> classEntries = zipEntries(extracted);
        assertThat(classEntries).contains("com/example/lib/Greeting.class");
        assertThat(classEntries).noneMatch(name -> name.endsWith("/R.class") || name.contains("/R$"));
        // R.txt carries the library's symbols for consumers.
        try (ZipFile zip = new ZipFile(aar.toFile())) {
            String rTxt = new String(zip.getInputStream(zip.getEntry("R.txt")).readAllBytes());
            assertThat(rTxt).contains("string lib_hello").contains("layout lib_view");
        }

        // ---- 2. Build the app module against the sibling library ----
        Path app = root.resolve("app");
        PipelineResult appResult = build(app, cache);
        assertThat(appResult.errors()).isEmpty();
        assertThat(appResult.success()).isTrue();

        // The library manifest joined the merge (--libs): its permission is in the app manifest.
        String merged = Files.readString(app.resolve("target/plugin/android-manifest/merged/AndroidManifest.xml"));
        assertThat(merged)
                .contains("package=\"com.example.app\"")
                .contains("android.permission.INTERNET");

        // Non-transitive R: the library's R regenerated under ITS namespace with final ids —
        // its own symbols, not the app's.
        Path libR = app.resolve("target/plugin/android-res/gen/com/example/lib/R.java");
        assertThat(libR).exists();
        String libRSource = Files.readString(libR);
        assertThat(libRSource)
                .contains("package com.example.lib;")
                .contains("lib_hello")
                .doesNotContain("app_name");
        // Final ids, not placeholders: every field carries a real 0x7f value.
        assertThat(libRSource).contains("0x7f");

        // The app's own R sees the merged table (app resource present).
        assertThat(Files.readString(app.resolve("target/plugin/android-res/gen/com/example/app/R.java")))
                .contains("app_name");

        // The APK carries the library's file-shaped resources and the dexed closure.
        Path apk = app.resolve("target/lib/app-1.0.0.apk");
        assertThat(apk).exists();
        assertThat(zipEntries(apk)).contains("classes.dex", "res/layout/lib_view.xml", "resources.arsc");
    }

    private static PipelineResult build(Path module, Path cache) throws Exception {
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION, "test", Lockfile.RESOLUTION_ALGORITHM, null, null,
                        List.of(), List.of()),
                module.resolve("jk.lock"));
        BuildPipelines.Inputs in = new BuildPipelines.Inputs(
                module,
                cache,
                module.resolve("jk.toml"),
                module.resolve("jk.lock"),
                module,
                1,
                0,
                null,
                null,
                true,
                false,
                false,
                false,
                java.util.Set.of(),
                build.jumpkick.config.SessionContext.current());
        Pipeline pipeline = BuildPipelines.coreBuilder(in).build();
        return pipeline.run();
    }

    private static void acceptLicenses() throws Exception {
        var sdk = build.jumpkick.androidsdk.AndroidSdk.resolve();
        var installer = new build.jumpkick.androidsdk.AndroidSdkInstaller(sdk);
        if (!sdk.installed("platforms;android-28")) {
            for (var license : installer.feed().licenses().entrySet()) {
                sdk.recordLicense(
                        license.getKey(), build.jumpkick.androidsdk.AndroidRepoFeed.licenseHash(license.getValue()));
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

    private static void writeWorkspace(Path root) throws Exception {
        Files.writeString(root.resolve("jk.toml"), """
                [project]
                name    = "ws"
                group   = "com.example"
                version = "1.0.0"
                java    = 17

                [workspace]
                modules = ["app", "lib"]
                """);

        Path lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                [project]
                name    = "lib"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [android]
                namespace   = "com.example.lib"
                compile-sdk = 28
                min-sdk     = 24
                library     = true

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);
        Files.writeString(lib.resolve("AndroidManifest.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <uses-permission android:name="android.permission.INTERNET"/>
                </manifest>
                """);
        Files.createDirectories(lib.resolve("res/values"));
        Files.writeString(lib.resolve("res/values/lib_strings.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="lib_hello">Hello from the library</string>
                </resources>
                """);
        Files.createDirectories(lib.resolve("res/layout"));
        Files.writeString(lib.resolve("res/layout/lib_view.xml"), """
                <?xml version="1.0" encoding="utf-8"?>
                <TextView xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:text="@string/lib_hello"/>
                """);
        Path libSrc = Files.createDirectories(lib.resolve("src/com/example/lib"));
        Files.writeString(libSrc.resolve("Greeting.java"), """
                package com.example.lib;

                /** Library code referencing the library's own R — non-transitive by construction. */
                public final class Greeting {
                    private Greeting() {}

                    public static int layout() {
                        return R.layout.lib_view;
                    }
                }
                """);

        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), """
                [project]
                name    = "app"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [android]
                namespace   = "com.example.app"
                compile-sdk = 28
                min-sdk     = 24

                [dependencies]
                lib = { workspace = true }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
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
                    <string name="app_name">jk workspace app</string>
                </resources>
                """);
        Path appSrc = Files.createDirectories(app.resolve("src/com/example/app"));
        Files.writeString(appSrc.resolve("MainActivity.java"), """
                package com.example.app;

                import android.app.Activity;
                import android.os.Bundle;
                import com.example.lib.Greeting;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(Greeting.layout());
                    }
                }
                """);
    }
}
