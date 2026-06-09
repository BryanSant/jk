// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    application
    alias(libs.plugins.graalvm.native)
}

description = "jk command-line entrypoint"

dependencies {
    implementation(project(":runtime"))
    implementation(project(":core"))
    implementation(project(":io"))
    // Shared NDJSON reader + WorkerProcess launch helper for worker-driving commands.
    implementation(project(":plugin-api"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    implementation(project(":engine"))
    implementation(project(":supply-chain"))
    implementation(project(":image"))
    implementation(project(":compat"))

    // JLine 4 FFM terminal provider for raw-mode TUI (jk init wizard).
    // FFM backend requires JDK 22+; the GraalVM-compiled binary embeds the
    // FFM downcalls natively. Reflection/resource hints live under
    // src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/.
    implementation(libs.jline.terminal.ffm)

    testImplementation(project(":supply-chain-testkit"))
    // JdkCommandTest builds xz-compressed feed fixtures via XZCompressorOutputStream.
    // GitSourceMaterializerTest builds a local git "library" repo fixture with jgit.
    // GitSourceMaterializerTest uses a local git fixture (built with system git or git-runner)
    // testImplementation(libs.jgit)  -- removed, tests use system git or git-runner worker
}

// Tests that fork child-JVM workers locate each jar via a system-property override
// until workers ship to Maven Central. Resolve each worker jar here and pass its
// path to the test JVM so tests are self-contained (no `installLocalCas` needed).
val kotlinWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val testRunnerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val auditWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val publishWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val imageWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val compatWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val gitWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies {
    kotlinWorkerJar(project(":kotlin-compiler"))
    testRunnerJar(project(":test-runner"))
    auditWorkerJar(project(":audit-runner"))
    publishWorkerJar(project(":publish-runner"))
    imageWorkerJar(project(":image-runner"))
    compatWorkerJar(project(":compat-runner"))
    gitWorkerJar(project(":git-runner"))
}
tasks.withType<Test>().configureEach {
    dependsOn(kotlinWorkerJar, testRunnerJar, auditWorkerJar, publishWorkerJar,
              imageWorkerJar, compatWorkerJar, gitWorkerJar)
    doFirst {
        systemProperty("jk.kotlin.worker.jar",  kotlinWorkerJar.singleFile.absolutePath)
        systemProperty("jk.test.runner.jar",    testRunnerJar.singleFile.absolutePath)
        systemProperty("jk.audit.worker.jar",   auditWorkerJar.singleFile.absolutePath)
        systemProperty("jk.publish.worker.jar", publishWorkerJar.singleFile.absolutePath)
        systemProperty("jk.image.worker.jar",   imageWorkerJar.singleFile.absolutePath)
        systemProperty("jk.compat.worker.jar",  compatWorkerJar.singleFile.absolutePath)
        systemProperty("jk.git.worker.jar",     gitWorkerJar.singleFile.absolutePath)
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
        // Push every heavy dep to lazy init. Build-time <clinit> is faster
        // at runtime but blows up .svm_heap with cached objects we may never
        // touch. Each package below is a known large contributor.
        buildArgs.add("--initialize-at-run-time=" + listOf(
            "org.bouncycastle",            // crypto (GPG signing)
            "dev.sigstore",                // sigstore-java + transitives
            "com.google",                  // Jib-core + Guava + Protobuf
            "org.cyclonedx",               // CycloneDX SBOM
            "org.spdx",                    // SPDX SBOM
            "org.eclipse.jgit",            // git client
            // gRPC client (sigstore-java). Pulls in netty via grpc-netty-shaded,
            // whose classes live under io.grpc.netty.shaded.io.netty.* — so the
            // `io.grpc` prefix here also covers the bundled netty.
            "io.grpc",
            "org.jline"                    // FFM Linker/Arena lookups must run at image-runtime
        ).joinToString(","))
    }
}

// JLine 4 FFM terminal provider ships native-image hints; we supplement them
// at src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/
// with reflection-config.json and resource-config.json bootstrapped via the
// GraalVM tracing agent against the JVM wizard (see plan §8d).
