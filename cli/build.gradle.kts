// SPDX-License-Identifier: Apache-2.0

import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
    application
    alias(libs.plugins.graalvm.native)
}

description = "jk command-line entrypoint"

dependencies {
    // :runtime absorbed into :host
    implementation(project(":host"))
    implementation(project(":core"))
    implementation(project(":io"))
    // Shared NDJSON reader + WorkerProcess launch helper for worker-driving commands.
    implementation(project(":plugin-api"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    // :engine absorbed into :host
    // supply-chain deleted: PolicyChecker moved to :core
    // image deleted: ImageConfig moved to :core
    // compat deleted: tool classes moved to :toolchain, bridge classes moved to :compat-runner

    // JLine 4 FFM terminal provider for raw-mode TUI (jk init wizard).
    // FFM backend requires JDK 22+; the GraalVM-compiled binary embeds the
    // FFM downcalls natively. Reflection/resource hints live under
    // src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/.
    implementation(libs.jline.terminal.ffm)

    // supply-chain-testkit deleted: GpgTestFixture copied to cli/src/test and publish-runner/src/test
    testImplementation(libs.bouncycastle.bcpg)
    // JdkCommandTest builds xz-compressed feed fixtures via XZCompressorOutputStream.
    // GitSourceMaterializerTest builds a local git "library" repo fixture with jgit.
    // GitSourceMaterializerTest uses a local git fixture (built with system git or git-runner)
    // testImplementation(libs.jgit)  -- removed, tests use system git or git-runner worker
}

// jk-host SHA resource: :host depends on :runtime so can't be hashed there.
// The CLI is the right place — it's what locates the host jar at runtime.
val hostJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { hostJar(project(":host")) }
val artifact = "jk-host"
val hostShaOutDir = "generated/resources/host-sha/$artifact"
val hostShaOutFile = layout.buildDirectory.file("$hostShaOutDir/META-INF/$artifact-sha256.txt")
val writeHostSha by tasks.registering {
    inputs.files(hostJar)
    outputs.file(hostShaOutFile)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256").digest(hostJar.singleFile.readBytes())
        hostShaOutFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(digest.joinToString("") { "%02x".format(it.toInt() and 0xff) })
        }
    }
}
sourceSets.named("main") { resources.srcDir(layout.buildDirectory.dir(hostShaOutDir)) }
tasks.named("processResources") { dependsOn(writeHostSha) }
tasks.named("sourcesJar") { dependsOn(writeHostSha) }

// Tests that fork child-JVM workers locate each jar via a system-property override
// until workers ship to Maven Central. Resolve each worker jar here and pass its
// path to the test JVM so tests are self-contained (no `installLocalCas` needed).
val kotlinWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val testRunnerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val auditorWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val publisherWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val imageBuilderWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val compatBridgeWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val gitClientWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies {
    kotlinWorkerJar(project(":kotlin-compiler"))
    testRunnerJar(project(":test-runner"))
    auditorWorkerJar(project(":auditor"))
    publisherWorkerJar(project(":publisher"))
    imageBuilderWorkerJar(project(":image-builder"))
    compatBridgeWorkerJar(project(":compat-bridge"))
    gitClientWorkerJar(project(":git-client"))
}
tasks.withType<Test>().configureEach {
    dependsOn(kotlinWorkerJar, testRunnerJar, auditorWorkerJar, publisherWorkerJar,
              imageBuilderWorkerJar, compatBridgeWorkerJar, gitClientWorkerJar)
    doFirst {
        systemProperty("jk.kotlin.worker.jar",       kotlinWorkerJar.singleFile.absolutePath)
        systemProperty("jk.test.runner.jar",         testRunnerJar.singleFile.absolutePath)
        systemProperty("jk.auditor.worker.jar",      auditorWorkerJar.singleFile.absolutePath)
        systemProperty("jk.publisher.worker.jar",    publisherWorkerJar.singleFile.absolutePath)
        systemProperty("jk.image-builder.worker.jar", imageBuilderWorkerJar.singleFile.absolutePath)
        systemProperty("jk.compat-bridge.worker.jar", compatBridgeWorkerJar.singleFile.absolutePath)
        systemProperty("jk.git-client.worker.jar",   gitClientWorkerJar.singleFile.absolutePath)
    }
}

application {
    mainClass.set("dev.jkbuild.cli.Jk")
    applicationName = "jk"
}

graalvmNative {
    binaries.named("main") {
        imageName.set("jk")
        mainClass.set("dev.jkbuild.cli.Jk")
        // The plugin's "main" binary is supposed to default to executable,
        // but the 0.10.4 / GraalVM 25 combination defaults to shared library
        // on this host. Force the executable mode explicitly.
        sharedLibrary.set(false)

        // Size-focused build args.
        //
        // -Ob       Optimise for build time = smaller .text than -O2 (default).
        //           We give up some runtime perf; for a short-lived CLI the
        //           startup wins from a smaller binary outweigh it.
        // -march=compatibility
        //           Single-arch baseline code, no per-CPU dispatch tables.
        // --gc=epsilon
        //           No-op GC. Safe for short verbs; long-running ones leak
        //           until process exit but jk is single-shot.
        buildArgs.add("-Ob")
        buildArgs.add("-march=compatibility")
        buildArgs.add("--gc=epsilon")
        // JLine 4 FFM's signal handler uses Arena.ofShared(), gated behind this
        // flag in GraalVM 25. Without it the wizard crashes on Signal.INT setup.
        buildArgs.add("-H:+SharedArenaSupport")
        // Silence the FFM "restricted method" runtime warning. Without this,
        // every wizard invocation prints a 4-line WARNING block before the UI.
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
        // Push heavy deps to lazy init. Build-time <clinit> is faster at
        // runtime but blows up .svm_heap with cached objects we may never
        // touch. The crypto/SBOM/git/Jib closures (bouncycastle, sigstore,
        // grpc, cyclonedx, spdx, jgit, com.google) live in forked workers, not
        // on the binary's classpath, so jline is the only contributor left:
        // its FFM Linker/Arena lookups must run at image-runtime regardless.
        buildArgs.add("--initialize-at-run-time=org.jline")
    }
}

// JLine 4 FFM terminal provider ships native-image hints; we supplement them
// at src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/
// with reflection-config.json and resource-config.json bootstrapped via the
// GraalVM tracing agent against the JVM wizard (see plan §8d).
