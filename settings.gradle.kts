// SPDX-License-Identifier: Apache-2.0

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jk"

// ---------------------------------------------------------------------------
// Cross-daemon build serialization.
//
// Gradle happily lets several daemons execute builds in this checkout at the
// same time — nothing locks task *output* directories across processes. That
// happens routinely here (multiple terminal/agent sessions building the same
// tree), and the usual casualty is :cli-engine:test, the longest task: a
// second build's test task wipes cli-engine/build/test-results/test/binary/
// while the first is still writing it, and the first build dies at
// result-collection time with an opaque `java.io.EOFException` (or
// NoSuchFileException on in-progress-results-generic.bin) and zero failing
// tests. Diagnosed 2026-07-09 from overlapping daemon logs (six daemons
// spawned 09:23–09:34, each new one because the previous was busy).
//
// Fix: hold an exclusive OS file lock for the entire build. Acquired eagerly
// during settings evaluation; released when Gradle closes the build service
// at build completion (Gradle 9 removed Gradle.buildFinished). A concurrent
// invocation blocks here — with a message — until the running build finishes.
//
// NOTE: if the configuration cache is ever enabled, settings scripts stop
// running on cache hits, so this eager .get() would too — move the service
// reference onto every task (Task.usesService) at that point.
// ---------------------------------------------------------------------------
abstract class CrossDaemonBuildLock : BuildService<CrossDaemonBuildLock.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val lockFile: Property<String>
    }

    private val channel: FileChannel
    private val lock: FileLock?

    init {
        val path = Path.of(parameters.lockFile.get())
        Files.createDirectories(path.parent)
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        lock = try {
            channel.tryLock() ?: run {
                println("Another Gradle build is running in this checkout — waiting for it to finish…")
                channel.lock()
            }
        } catch (e: OverlappingFileLockException) {
            // This daemon already holds the lock (nested build in the same JVM):
            // it is serialized by definition — don't double-lock, don't fail.
            null
        }
    }

    override fun close() {
        lock?.release()
        channel.close()
    }
}

gradle.sharedServices
    .registerIfAbsent("crossDaemonBuildLock", CrossDaemonBuildLock::class) {
        parameters.lockFile.set(rootDir.resolve(".gradle/cross-daemon-build.lock").absolutePath)
    }
    .get() // eager: take the lock now, before any task can touch shared outputs

include(
    // Kernel — universal capabilities (no plugin deps)
    ":model",
    ":core",
    ":plugin-api",
    ":io",
    ":resolver",
    ":toolchain",
    ":toolchain-jdk",
    ":client-io",
    // Plugins — first-party, shipped with jk
    ":test-runner",
    ":kotlin-compiler",
    ":java-compiler",
    ":auditor",        // was :audit-runner
    ":publisher",      // was :publish-runner
    ":image-builder",  // was :image-runner
    ":compat-bridge",  // was :compat-runner
    ":git-client",     // was :git-runner
    ":formatter",      // jk format — Spotless-wrapped Java/Kotlin formatter
    // Host + CLI
    ":engine-api",
    ":engine",
    ":cli",
    ":cli-engine",
)

// Kernel modules live under kernel/
project(":model").projectDir     = file("kernel/model")
project(":core").projectDir      = file("kernel/core")
project(":io").projectDir        = file("kernel/io")
project(":resolver").projectDir  = file("kernel/resolver")
project(":toolchain").projectDir = file("kernel/toolchain")
project(":toolchain-jdk").projectDir = file("kernel/toolchain-jdk")
project(":client-io").projectDir = file("kernel/client-io")
project(":engine-api").projectDir  = file("kernel/engine-api")
project(":engine").projectDir      = file("kernel/engine")

// Plugin modules live under plugins/
project(":test-runner").projectDir    = file("plugins/test-runner")
project(":kotlin-compiler").projectDir = file("plugins/kotlin-compiler")
project(":java-compiler").projectDir  = file("plugins/java-compiler")
project(":auditor").projectDir        = file("plugins/auditor")
project(":publisher").projectDir      = file("plugins/publisher")
project(":image-builder").projectDir  = file("plugins/image-builder")
project(":compat-bridge").projectDir  = file("plugins/compat-bridge")
project(":git-client").projectDir     = file("plugins/git-client")
project(":formatter").projectDir      = file("plugins/formatter")
