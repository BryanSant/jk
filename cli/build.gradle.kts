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

    // JLine 4 FFM terminal provider for raw-mode TUI (jk init wizard).
    // FFM backend requires JDK 22+; the GraalVM-compiled binary embeds the
    // FFM downcalls natively. Reflection/resource hints live under
    // src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/.
    implementation(libs.jline.terminal.ffm)

    testImplementation(testFixtures(project(":supply-chain")))
    // JdkCommandTest builds xz-compressed feed fixtures via XZCompressorOutputStream.
    testImplementation(libs.commons.compress)
    testImplementation(libs.tukaani.xz)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Aproject=dev.jkbuild.cli")
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
