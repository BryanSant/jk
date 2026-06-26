// SPDX-License-Identifier: Apache-2.0
// Absorbs :engine and :runtime (Phase 5 module reorg).

plugins {
    id("jk.java-conventions")
}

description = "jk build engine: the Goal/Phase scheduler and the build pipeline, " +
        "run in-process by the CLI. Absorbs the former :engine and :runtime modules."

dependencies {
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

