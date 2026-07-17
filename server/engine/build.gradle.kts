// SPDX-License-Identifier: Apache-2.0
// Absorbs :engine and :runtime (Phase 5 module reorg).

plugins {
    id("jk.java-conventions")
}

description = "jk build engine: the Pipeline/Step scheduler and the build pipeline, " +
        "run in-process by the CLI. Absorbs the former :engine and :runtime modules."

dependencies {
    // The client<->engine wire contract (protocol codec, EnginePaths, build DTOs) — api so a
    // caller of BuildService sees the DTO types (slim-client Stage 5).
    api(project(":wire"))
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":plugin-sdk"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    // The web dashboard's static assets ride the engine's runtime classpath as /www/* (served by
    // StaticContent) and get bundled into the jk-engine fat jar. Kept resources-only + runtimeOnly
    // so the assets never touch the compile classpath and the native CLI never links them.
    runtimeOnly(project(":web"))
    // ASM — used by the incremental Java compiler's dependency extraction
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)

    // JGit is the in-process fallback git backend (used when no `git` command is found) and also
    // builds the git fixtures in tests. The native CLI (:cli) does not depend on :engine, so this
    // never enters a native image — only the :cli-engine JVM daemon jar.
    implementation(libs.jgit)
}

// ---------------------------------------------------------------------------
// Worker jar paths for tests (WorkerJavacTest, KotlinWorkerSetupTest, etc.)
val testRunnerJarCfg by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    testRunnerJarCfg(project(":test-runner"))
}

val javaCompilerWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { javaCompilerWorkerJar(project(":java-compiler")) }
tasks.withType<Test>().configureEach {
    // MemoryProbe's host_statistics64 FFM downcall (macOS memory read).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(javaCompilerWorkerJar, testRunnerJarCfg)
    doFirst {
        systemProperty("jk.java.plugin.jar", javaCompilerWorkerJar.singleFile.absolutePath)
        systemProperty("jk.test.runner.jar", testRunnerJarCfg.singleFile.absolutePath)
    }
}

// Pass the spring-boot worker jar to tests (the plugin build runtime forks it for Boot projects).
val testSpringBootWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testSpringBootWorkerJar(project(":spring-boot")) }
tasks.withType<Test>().configureEach {
    dependsOn(testSpringBootWorkerJar)
    doFirst { systemProperty("jk.spring-boot.plugin.jar", testSpringBootWorkerJar.singleFile.absolutePath) }
}

// Pass the android worker jar to tests (the Android-spike integration test forks it).
val testAndroidWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testAndroidWorkerJar(project(":android")) }
tasks.withType<Test>().configureEach {
    dependsOn(testAndroidWorkerJar)
    doFirst { systemProperty("jk.android.plugin.jar", testAndroidWorkerJar.singleFile.absolutePath) }
}

// Pass the protobuf worker jar to tests (the protoc codegen integration test forks it).
val testProtobufWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testProtobufWorkerJar(project(":protobuf")) }
tasks.withType<Test>().configureEach {
    dependsOn(testProtobufWorkerJar)
    doFirst { systemProperty("jk.protobuf.plugin.jar", testProtobufWorkerJar.singleFile.absolutePath) }
}

// Pass the shrink worker jar to tests (the R8 shrunk-jar integration test forks it).
val testShrinkWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testShrinkWorkerJar(project(":shrink")) }
tasks.withType<Test>().configureEach {
    dependsOn(testShrinkWorkerJar)
    doFirst { systemProperty("jk.shrink.plugin.jar", testShrinkWorkerJar.singleFile.absolutePath) }
}

// Pass the kotlin-compiler worker jar to tests (the KSP/Room/Hilt gate compiles Kotlin).
val testKotlinWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testKotlinWorkerJar(project(":kotlin-compiler")) }
tasks.withType<Test>().configureEach {
    dependsOn(testKotlinWorkerJar)
    doFirst { systemProperty("jk.kotlin.plugin.jar", testKotlinWorkerJar.singleFile.absolutePath) }
}

// Pass the auditor jar path to tests (EngineServerTest's hosted jk audit round-trip forks it).
val testAuditorWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testAuditorWorkerJar(project(":auditor")) }
tasks.withType<Test>().configureEach {
    dependsOn(testAuditorWorkerJar)
    doFirst { systemProperty("jk.auditor.plugin.jar", testAuditorWorkerJar.singleFile.absolutePath) }
}
