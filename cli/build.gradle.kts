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

    // Native-image Feature API (EngineDetachFeature registers the setsid(2) FFM downcall at
    // image-build time). compileOnly: the image builder supplies these classes itself; nothing
    // references them on a hosted JVM.
    compileOnly(libs.graalvm.nativeimage)

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
    // The fast unit-test suite has no real `jk` binary to exec as an engine and doesn't isolate
    // ~/.jk/state/engine/ per test — see BuildCommand.engineDisabledForTests()'s javadoc. The engine
    // transport itself is covered by EngineServer/EngineClient tests and manual verification against
    // the real native binary (docs/engine.md), not this suite.
    systemProperty("jk.test.noEngine", "true")
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
    // Mirror the native binary's runtime profile for the JVM dist: SerialGC + a small,
    // capped heap. The engine spawn overrides the sizing for its own process via JK_OPTS
    // (last in the start script's JVM-arg order, so its -Xms/-Xmx win).
    // --enable-native-access: PosixDetach's setsid(2) FFM downcall (engine role) without the
    // JDK's restricted-method warning; mirrors the native binary's build arg.
    applicationDefaultJvmArgs =
            listOf("-XX:+UseSerialGC", "-Xms24m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    binaries.named("main") {
        imageName.set("jk")
        mainClass.set("dev.jkbuild.cli.Jk")
        // The plugin's "main" binary is supposed to default to executable,
        // but the 0.10.4 / GraalVM 25 combination defaults to shared library
        // on this host. Force the executable mode explicitly.
        sharedLibrary.set(false)

        // Size-first build args. The jk binary's primary UX budget is its download +
        // on-disk size and shell-integration startup latency; per-verb CPU work is
        // shrinking as the CLI delegates the heavy lifting (hashing, compiling,
        // packaging) to the resident engine and its forked workers.
        //
        // -Os       Optimize for size. (History: was -O3 + -march=x86-64-v3, tuned when
        //           the CLI process itself did the CAS/ClasspathFingerprint SHA-256
        //           work — the SIMD -march bought ≈1.5x on no-op builds then. The CLI
        //           and engine still share this one binary, so dropping -march trades
        //           some engine-side hashing speed for a smaller binary; when the
        //           slim-client split lands, the engine artifact can re-tune for speed
        //           independently.)
        // --gc=serial
        //           Generational serial GC. Small/fast for short verbs and a ≤256 MiB
        //           engine heap alike, and — unlike epsilon — it actually reclaims, so
        //           verbs that stream data don't accumulate every transient byte until
        //           the process dies.
        // -R:MaxHeapSize=134217728
        //           Hard 128 MiB max heap for the CLI process. jk's own work is tiny;
        //           the cap turns any runaway allocation into a fast, loud OOM instead
        //           of dragging the machine into swap. Heavy work runs in the engine
        //           (spawned with its own -Xms/-Xmx, which override this baked default)
        //           and in forked worker JVMs tuned via JvmOptions.
        // -R:MinHeapSize=25165824
        //           24 MiB initial heap — sized to what a trivial verb actually uses
        //           (`jk --help` measured ~19 MiB RSS), so the smallest commands fit in
        //           the floor without a growth step, while anything bigger still grows
        //           lazily toward the 128 MiB cap.
        buildArgs.add("-Os")
        buildArgs.add("--gc=serial")
        buildArgs.add("-R:MaxHeapSize=134217728")
        buildArgs.add("-R:MinHeapSize=25165824")
        // JLine 4 FFM's signal handler uses Arena.ofShared(), gated behind this
        // flag in GraalVM 25. Without it the wizard crashes on Signal.INT setup.
        buildArgs.add("-H:+SharedArenaSupport")
        // Silence the FFM "restricted method" runtime warning. Without this,
        // every wizard invocation prints a 4-line WARNING block before the UI.
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
        // Registers the setsid(2) downcall descriptor (PosixDetach) — FFM downcalls must be
        // registered at image-build time or they throw at first use in the native binary.
        buildArgs.add("--features=dev.jkbuild.cli.nativeimage.EngineDetachFeature")
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
        // jline-native ships a resource-config with a broad "org/jline/nativ/.*"
        // pattern that embeds ALL platform native libs (Windows DLLs, Linux/macOS/
        // FreeBSD .so/.dylib for every arch) as image resources.  jk uses the FFM
        // terminal provider exclusively; the JNI/JNA fallback (JLineNativeLoader,
        // CLibrary, Kernel32, etc.) is reachable via jline-terminal's AbstractPty
        // but never exercised at runtime.  Exclude those cross-platform binaries
        // with -H:ExcludeResources so they are not baked into the image heap.
        buildArgs.add("-H:ExcludeResources=org/jline/nativ/.*")
    }
}

// JLine 4 FFM terminal provider ships native-image hints; we supplement them
// at src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/
// with reflection-config.json and resource-config.json bootstrapped via the
// GraalVM tracing agent against the JVM wizard (see plan §8d).
