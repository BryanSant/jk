// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    application
    alias(libs.plugins.graalvm.native)
}

description = "jk command-line entrypoint"

dependencies {
    // :runtime absorbed into :host
    implementation(project(":engine"))
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
    // A cache the end-to-end tests share (SharedTestCache) so the real deps they
    // resolve — Kotlin compiler, JUnit, … — are fetched from Maven Central once
    // and reused across tests and runs, instead of hammering it from a fresh
    // @TempDir cache per test. Persisted under build/ (cleared by `gradle clean`).
    systemProperty("jk.test.cache.dir",
            layout.buildDirectory.dir("test-shared-cache").get().asFile.absolutePath)
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

        // Runtime-perf build args.
        //
        // -O3       Max optimisation. (Was -Ob, build-time/size focused.) -O3 alone
        //           is only ~10% here; the real win is letting -march light up the
        //           CPU's vector/crypto instructions below.
        // -march    Per-CPU baseline. native-image compiles for the BUILD HOST's arch,
        //           so this is keyed off os.arch. On x86-64 we target x86-64-v3 (AVX2 +
        //           BMI2 + FMA + F16C — portable to ~2015+ CPUs); benchmarked ≈1.5x
        //           faster than -march=compatibility on jk's hashing/orchestration
        //           (no-op `jk build`), since the CAS + ClasspathFingerprint SHA-256
        //           dominates jk's own time and gets SIMD-accelerated. We deliberately
        //           do NOT use -march=native (≈1.9x): it would only run on the build
        //           machine's exact CPU (its extra ~20% is AVX-512 + AES-NI + SHA-NI,
        //           none of which any portable -march level includes). aarch64/macOS
        //           builds fall back to `compatibility` for now — tune a crypto-capable
        //           baseline on aarch64 hardware in a follow-up.
        // --gc=serial
        //           Generational serial GC. Small/fast for short verbs, and —
        //           unlike epsilon — it actually reclaims, so verbs that stream
        //           data (sync/idea/build fetching artifacts) don't accumulate
        //           every transient byte until the process dies.
        // -R:MaxHeapSize=268435456
        //           Hard 256 MiB max heap for the CLI process. jk's own work is
        //           tiny; with a real collector under it, 256 MiB is plenty, and
        //           the cap turns any runaway allocation into a fast, loud OOM
        //           instead of dragging the whole machine into swap. Heavy work
        //           (compile/test) runs in forked worker JVMs tuned separately
        //           via JvmOptions, not in this process.
        val march = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> "x86-64-v3"
            else -> "compatibility"
        }
        buildArgs.add("-O3")
        buildArgs.add("-march=$march")
        buildArgs.add("--gc=serial")
        buildArgs.add("-R:MaxHeapSize=268435456")
        // -R:MinHeapSize=268435456
        //           Pin the minimum heap to the same 256 MiB (the -Xms half of
        //           the user's "-Xms/-Xmx 256 MiB" ask). The serial GC won't
        //           collect below this, so a short verb that stays under 256 MiB
        //           ideally never runs a GC cycle; pages aren't pre-touched, so
        //           a trivial command's RSS stays small.
        buildArgs.add("-R:MinHeapSize=268435456")
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
        // tomlj 1.1.1 was generated with ANTLR 4.11.1 but 4.13.2 ends up on the
        // runtime classpath via a transitive dep. ANTLR's static initializer prints
        // a version-mismatch warning on every startup. Initializing at build time
        // bakes the check into the image so it never runs at the user's terminal.
        buildArgs.add("--initialize-at-build-time=org.antlr")
    }
}

// JLine 4 FFM terminal provider ships native-image hints; we supplement them
// at src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/
// with reflection-config.json and resource-config.json bootstrapped via the
// GraalVM tracing agent against the JVM wizard (see plan §8d).
