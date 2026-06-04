// SPDX-License-Identifier: Apache-2.0

import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
}

description = "jk build engine: action graph, compile, test"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    // ClassRemapper drives a comprehensive type-reference traversal for the
    // incremental Java compiler's dependency extraction.
    implementation(libs.asm.commons)
    // JUnit Platform is no longer on engine's runtime classpath — `jk test`
    // forks a child JVM that runs jk-test-runner with the JUnit jars
    // sourced from the user's CAS (injected via LockOrchestrator). All
    // engine needs is jackson for parsing the runner's NDJSON event stream.
    implementation(libs.jackson.databind)
    // Kotlin support is now subprocess-based — SubprocessKotlincStrategy
    // execs <kotlin-home>/bin/kotlinc from a jk-installed distribution.
    // kotlin-compiler-embeddable (~50 MB) and kotlin-stdlib are no longer
    // on the classpath; the user-provided classpath still gets the
    // appropriate stdlib via the project's declared deps.
}

// jk-test-runner.jar is not embedded in cli.jar anymore. Instead the
// engine looks it up in the local CAS by its SHA-256 — once
// jk-test-runner is on Maven Central, normal `jk sync` populates it;
// in the meantime users (and the dev workflow) side-load it.
//
// To do that lookup we need the engine to KNOW the expected hash at
// runtime. Hash the test-runner jar at build time and write the hex
// digest into the engine's resources at
// META-INF/jk-test-runner-sha256.txt — engine reads it via classpath.
val testRunnerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    testRunnerJar(project(":test-runner"))
}
val writeRunnerSha by tasks.registering {
    val inputJar = testRunnerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/runner-sha/META-INF/jk-test-runner-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sb.toString())
        }
    }
}
sourceSets.named("main") {
    resources.srcDir(
            layout.buildDirectory.dir("generated/resources/runner-sha"))
}
tasks.named("processResources") {
    dependsOn(writeRunnerSha)
}
// sourcesJar (added by withSourcesJar() in the conventions plugin) bundles
// `allSource`, which includes the main source set's resource srcDirs. The
// generated runner-sha dir is one of those, so sourcesJar implicitly reads
// writeRunnerSha's output and Gradle 9's validator rejects undeclared
// task→task data flow. Wire it explicitly.
tasks.named("sourcesJar") {
    dependsOn(writeRunnerSha)
}

// WorkerJavacTest launches the jk-java-compiler worker as a subprocess; hand its
// jar to the test JVM via the same property the runtime locator honours.
val javaCompilerWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    javaCompilerWorkerJar(project(":java-compiler"))
}
tasks.withType<Test>().configureEach {
    dependsOn(javaCompilerWorkerJar)
    doFirst {
        systemProperty("jk.java.worker.jar", javaCompilerWorkerJar.singleFile.absolutePath)
    }
}
