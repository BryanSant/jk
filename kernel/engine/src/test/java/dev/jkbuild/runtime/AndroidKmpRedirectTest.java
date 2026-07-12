// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkbuild.config.JkBuildParser;
import dev.jkbuild.lock.Lockfile;
import dev.jkbuild.lock.LockfileReader;
import dev.jkbuild.model.JkBuild;
import dev.jkbuild.resolver.ResolveObserver;
import dev.jkbuild.run.Goal;
import dev.jkbuild.run.GoalResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * android-plan Phase 5, blocker 1: a Kotlin-Multiplatform root module (androidx compose
 * runtime-annotation — the exact artifact Now-in-Android trips over via androidx.activity)
 * resolves Gradle-style. The root locks as a POM-only alias (no artifact — checksum-null rows are
 * classpath-inert), the GMM-selected platform artifact carries the classes, and the un-selected
 * platform fallback never enters the graph — so nothing double-defines at dex time.
 *
 * <p>Network test against Google Maven; the CAS persists under build/ so repeat runs are warm.
 */
class AndroidKmpRedirectTest {

    @Test
    void android_project_selects_the_android_variant_and_aliases_the_root(@TempDir Path tmp) throws Exception {
        Lockfile lockfile = lockProject(tmp, """
                [project]
                name    = "kmp"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [android]
                namespace   = "com.example.kmp"
                compile-sdk = 34
                min-sdk     = 24

                [dependencies]
                runtime-annotation = { group = "androidx.compose.runtime", name = "runtime-annotation", version = "=1.9.0" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);

        Lockfile.Artifact root = artifact(lockfile, "androidx.compose.runtime:runtime-annotation");
        // POM-only alias: no artifact bytes, classpath-inert, sync-skipped.
        assertThat(root.checksum()).isNull();
        assertThat(root.path()).isNull();
        assertThat(root.deps())
                .anyMatch(d -> d.startsWith("androidx.compose.runtime:runtime-annotation-android@"));

        Lockfile.Artifact android = artifact(lockfile, "androidx.compose.runtime:runtime-annotation-android");
        assertThat(android.checksum()).startsWith("sha256:");
        assertThat(android.isAar()).isTrue();

        // The POM's -jvm fallback must NOT ride alongside the selected -android variant.
        assertThat(lockfile.artifacts())
                .noneMatch(a -> a.name().equals("androidx.compose.runtime:runtime-annotation-jvm"));
    }

    @Test
    void plain_jvm_project_selects_the_jvm_variant(@TempDir Path tmp) throws Exception {
        Lockfile lockfile = lockProject(tmp, """
                [project]
                name    = "kmpjvm"
                group   = "com.example"
                version = "1.0.0"
                java    = 17
                layout  = "simple"

                [dependencies]
                runtime-annotation = { group = "androidx.compose.runtime", name = "runtime-annotation", version = "=1.9.0" }

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                google  = "https://dl.google.com/dl/android/maven2/"
                """);

        Lockfile.Artifact root = artifact(lockfile, "androidx.compose.runtime:runtime-annotation");
        assertThat(root.checksum()).isNull();
        assertThat(root.deps()).anyMatch(d -> d.startsWith("androidx.compose.runtime:runtime-annotation-jvm@"));
        assertThat(artifact(lockfile, "androidx.compose.runtime:runtime-annotation-jvm").checksum())
                .startsWith("sha256:");
        assertThat(lockfile.artifacts())
                .noneMatch(a -> a.name().equals("androidx.compose.runtime:runtime-annotation-android"));
    }

    private static Lockfile lockProject(Path tmp, String jkToml) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("app"));
        Path cache = Path.of(System.getProperty("user.dir"), "build", "android-spike-cache");
        Files.writeString(project.resolve("jk.toml"), jkToml);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        Goal lock = LockGoals.lockGoal(
                project, build, cache, null, java.util.List.of(), true, false, ResolveObserver.NOOP, null);
        GoalResult result = lock.run();
        assertThat(result.errors()).isEmpty();
        assertThat(result.success()).isTrue();
        return LockfileReader.read(project.resolve("jk.lock"));
    }

    private static Lockfile.Artifact artifact(Lockfile lockfile, String name) {
        return lockfile.artifacts().stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("not locked: " + name));
    }
}
