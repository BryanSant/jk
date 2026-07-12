// SPDX-License-Identifier: Apache-2.0
// Absorbs :engine and :runtime (Phase 5 module reorg).

plugins {
    id("jk.java-conventions")
}

description = "jk build engine: the Goal/Phase scheduler and the build pipeline, " +
        "run in-process by the CLI. Absorbs the former :engine and :runtime modules."

dependencies {
    // The client<->engine wire contract (protocol codec, EnginePaths, build DTOs) — api so a
    // caller of BuildService sees the DTO types (slim-client Stage 5).
    api(project(":engine-api"))
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":plugin-api"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    // ASM — used by the incremental Java compiler's dependency extraction
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)

    testImplementation(libs.jgit)   // git test fixtures
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
        systemProperty("jk.java.worker.jar", javaCompilerWorkerJar.singleFile.absolutePath)
        systemProperty("jk.test.runner.jar", testRunnerJarCfg.singleFile.absolutePath)
    }
}

// Pass the git-client jar path to tests (GitFetcher forks it).
val testGitWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testGitWorkerJar(project(":git-client")) }
tasks.withType<Test>().configureEach {
    dependsOn(testGitWorkerJar)
    doFirst { systemProperty("jk.git-client.worker.jar", testGitWorkerJar.singleFile.absolutePath) }
}

// Pass the spring-boot worker jar to tests (the plugin build runtime forks it for Boot projects).
val testSpringBootWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testSpringBootWorkerJar(project(":spring-boot")) }
tasks.withType<Test>().configureEach {
    dependsOn(testSpringBootWorkerJar)
    doFirst { systemProperty("jk.spring-boot.worker.jar", testSpringBootWorkerJar.singleFile.absolutePath) }
}

// Pass the android worker jar to tests (the Android-spike integration test forks it).
val testAndroidWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testAndroidWorkerJar(project(":android")) }
tasks.withType<Test>().configureEach {
    dependsOn(testAndroidWorkerJar)
    doFirst { systemProperty("jk.android.worker.jar", testAndroidWorkerJar.singleFile.absolutePath) }
}

// Pass the protobuf worker jar to tests (the protoc codegen integration test forks it).
val testProtobufWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testProtobufWorkerJar(project(":protobuf")) }
tasks.withType<Test>().configureEach {
    dependsOn(testProtobufWorkerJar)
    doFirst { systemProperty("jk.protobuf.worker.jar", testProtobufWorkerJar.singleFile.absolutePath) }
}

// Pass the kotlin-compiler worker jar to tests (the KSP/Room/Hilt gate compiles Kotlin).
val testKotlinWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testKotlinWorkerJar(project(":kotlin-compiler")) }
tasks.withType<Test>().configureEach {
    dependsOn(testKotlinWorkerJar)
    doFirst { systemProperty("jk.kotlin.worker.jar", testKotlinWorkerJar.singleFile.absolutePath) }
}

// Pass the auditor jar path to tests (EngineServerTest's hosted jk audit round-trip forks it).
val testAuditorWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testAuditorWorkerJar(project(":auditor")) }
tasks.withType<Test>().configureEach {
    dependsOn(testAuditorWorkerJar)
    doFirst { systemProperty("jk.auditor.worker.jar", testAuditorWorkerJar.singleFile.absolutePath) }
}
