// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
}

description = "jk-test-runner: child-JVM entry point that drives JUnit Platform and " +
        "streams events back to the parent jk process as NDJSON on stdout."

dependencies {
    // The runner only needs the Launcher API at compile time. Engines and the
    // user's test classes are added to the child classpath at fork time by
    // jk's TestCommand — they don't belong here.
    implementation(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.test.runner.JkRunner",
                "Implementation-Title" to "jk-test-runner",
                "Implementation-Version" to project.version)
    }
}

/**
 * Copy the freshly-built test-runner jar into the developer's local
 * jk CAS at {@code ~/.jk/cache/sha256/AA/BB/<rest>}, keyed by the
 * jar's SHA-256. Simulates what `jk sync` will do once the runner
 * is published to Maven Central.
 *
 * <p>Run after any change to the runner: {@code ./gradlew
 * :test-runner:installLocalCas}. Pair with {@code :cli:installDist}
 * so the engine's expected-hash resource regenerates as well.
 */
tasks.register("installLocalCas") {
    description = "Side-load the freshly-built test-runner jar into ~/.jk/cache/sha256/<hash>"
    group = "jk"
    dependsOn(tasks.jar)
    val jarProvider = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jarProvider)
    doLast {
        val jar = jarProvider.get().asFile
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        val hex = sb.toString()
        val cacheRoot: File = System.getenv("JK_CACHE_DIR")?.let { File(it) }
                ?: System.getenv("JK_HOME")?.let { File(it).resolve("cache") }
                ?: File(System.getProperty("user.home"), ".jk/cache")
        val target = cacheRoot.resolve("sha256")
                .resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4))
                .resolve(hex.substring(4))
        target.parentFile.mkdirs()
        jar.copyTo(target, overwrite = true)
        println("Installed jk-test-runner ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}
