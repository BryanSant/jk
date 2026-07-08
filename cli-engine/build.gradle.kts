// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    application
}

description = "jk engine application: EngineMain (the engine JVM's entrypoint), the engine's " +
        "POSIX self-detach plumbing, and the ServiceLoader-discovered InProcessEngine that backs " +
        "jk --engine-server and the test-only in-process dispatch (slim-client Stage 5). The one " +
        "module that links both the CLI and the full engine kernel."

dependencies {
    implementation(project(":cli"))
    implementation(project(":engine"))
    // The in-process command host reaches the same kernel surface the engine's handlers do.
    implementation(project(":model"))
    implementation(project(":core"))
    implementation(project(":client-io"))
    implementation(project(":io"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    implementation(project(":toolchain-jdk"))
    implementation(project(":engine-api"))
    implementation(project(":plugin-api"))

    // EngineMain ignores terminal SIGINT/SIGHUP through JLine's signal registry (same library the
    // CLI's TUI already ships).
    implementation(libs.jline.terminal.ffm)

    // supply-chain-testkit deleted: GpgTestFixture copied to this test suite and publisher's
    testImplementation(libs.bouncycastle.bcpg)
    // JdkCommandTest builds xz-compressed feed fixtures via XZCompressorOutputStream.
    // GitSourceMaterializerTest uses a local git fixture (built with system git or git-runner)
}


// The CLI test suite lives in THIS module since the Stage 5 dependency cut: it exercises the
// command layer's engine-backed paths in-process (jk.test.noEngine), which needs the full kernel
// — exactly what this module links and :cli deliberately does not.
//
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
    // The TUI/highlighting tests assert ANSI escape sequences, and Theme/GlobalConfig read the
    // ambient environment (TERM=dumb, CI=true/1, NO_COLOR all disable color). Pin the test JVMs'
    // environment so the suite is deterministic on every host — a GitHub runner (TERM=dumb,
    // CI=true) fails ~100 rendering tests otherwise.
    environment("TERM", "xterm-256color")
    environment("CI", "false")
    environment("NO_COLOR", "")
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
    // The JVM distribution (installDist) deliberately stays a single artifact with the client
    // entrypoint: `jk` verbs run the thin client code, and `jk --engine-server` reaches EngineMain
    // through the InProcessEngine seam — everything is on this module's classpath.
    mainClass.set("dev.jkbuild.cli.Jk")
    applicationName = "jk"
    // The client verbs' runtime profile: SerialGC + a small, capped heap (the same numbers the
    // native client bakes as -R: defaults). The engine spawn overrides the sizing for its own
    // process via JK_OPTS (last in the start script's JVM-arg order, so its -Xms/-Xmx win).
    // --enable-native-access: PosixDetach's setsid(2) FFM downcall (engine role) without the
    // JDK's restricted-method warning.
    applicationDefaultJvmArgs =
            listOf("-XX:+UseSerialGC", "-Xms24m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED")
}

// The engine artifact of the native dist (docs/engine.md "Two artifacts"): this module's runtime
// classpath as a plain jar directory, shipped as libexec/jk-engine/ next to the native jk client,
// which spawns it as `<managed-jdk>/bin/java … -cp 'libexec/jk-engine/*' dev.jkbuild.cli.EngineMain`.
// The engine is deliberately NOT a native image: it is long-lived, so HotSpot's JIT and SHA-256
// intrinsics serve its hashing-heavy hot path, and its heap/GC profile is plain JVM flags on the
// spawn line (SerialGC, -Xms96m -Xmx256m from JkEngineConfig) instead of baked -R: defaults.
val installEngineLibs by tasks.registering(Sync::class) {
    description = "Installs the engine's runtime classpath as build/libexec/jk-engine/*.jar"
    group = "distribution"
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("libexec/jk-engine"))
}
