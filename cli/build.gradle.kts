// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    application
    alias(libs.plugins.graalvm.native)
}

description = "jk command-line entrypoint"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    implementation(project(":engine"))
    implementation(project(":supply-chain"))
    implementation(project(":image"))
    implementation(project(":compat"))

    implementation(libs.picocli)
    // picocli-codegen runs at compile time to emit
    // META-INF/native-image/picocli-generated/<project>/reflection-config.json
    // for every @Command-annotated class. Without this the native-image build
    // strips the annotation metadata picocli needs at runtime, and the binary
    // throws "AutoHelpMixin is not a command" on first call.
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")

    testImplementation(testFixtures(project(":supply-chain")))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Aproject=dev.buildjk.cli")
}

application {
    mainClass.set("dev.buildjk.cli.Jk")
    applicationName = "jk"
}

graalvmNative {
    binaries.named("main") {
        imageName.set("jk")
        mainClass.set("dev.buildjk.cli.Jk")
        // The plugin's "main" binary is supposed to default to executable,
        // but the 0.10.4 / GraalVM 25 combination defaults to shared library
        // on this host. Force the executable mode explicitly.
        sharedLibrary.set(false)

        // Size-focused build args. A default build was ~187 MB
        // (.text 110 MB compiled code + .svm_heap 85 MB build-time
        // initialised state from kotlin-compiler-embeddable).
        //
        // -Ob       Optimise for build time = smaller .text than -O2 (default).
        //           We give up some runtime perf; for a short-lived CLI the
        //           startup wins from a smaller binary outweigh it.
        // -march=compatibility
        //           Single-arch baseline code, no per-CPU dispatch tables.
        // --gc=epsilon
        //           No-op GC. Safe for short verbs; long-running ones leak
        //           until process exit but jk is single-shot.
        // --initialize-at-run-time=org.jetbrains.kotlin
        //           Don't snapshot Kotlin compiler's static state into the
        //           image heap; let it bootstrap lazily when KotlincDriver
        //           is actually invoked. The largest .svm_heap saver.
        buildArgs.add("-Ob")
        buildArgs.add("-march=compatibility")
        buildArgs.add("--gc=epsilon")
        // Push every heavy dep to lazy init. Build-time <clinit> is faster
        // at runtime but blows up .svm_heap with cached objects we may never
        // touch. Each package below is a known large contributor.
        buildArgs.add("--initialize-at-run-time=" + listOf(
            "org.jetbrains.kotlin",        // kotlin-compiler-embeddable (huge)
            "org.bouncycastle",            // crypto (GPG signing)
            "dev.sigstore",                // sigstore-java + transitives
            "com.google",                  // Jib-core + Guava + Protobuf
            "org.cyclonedx",               // CycloneDX SBOM
            "org.spdx",                    // SPDX SBOM
            "org.eclipse.jgit",            // git client
            "io.netty",                    // pulled in by sigstore-java
            "io.grpc"                      // gRPC client (sigstore-java)
        ).joinToString(","))
    }
}

// kotlin-compiler-embeddable (pulled in via :engine for the KotlincDriver)
// ships shaded JLine classes plus META-INF/native-image/org.jline/<mod>/
// native-image.properties files that reference reflection-config.json /
// resource-config.json — but those JSON files aren't shaded in. Without
// them, native-image bails. jk never invokes JLine, so we provide empty
// stubs under cli/src/main/resources/META-INF/native-image/org.jline/.
