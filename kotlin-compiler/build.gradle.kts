// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

// Published to the local Maven repo (`./gradlew publishToMavenLocal`) so `jk sync`
// can pull the worker into the CAS. Coordinates + version must match
// JkWorkerSync.GROUP / artifactId and dev.jkbuild.util.JkVersion.VERSION.
group = "dev.jkbuild"
version = "0.1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("worker") {
            artifactId = "jk-kotlin-compiler"
            from(components["java"])
        }
    }
}

description = "jk-kotlin-compiler: child-JVM worker that drives the Kotlin Build Tools API " +
        "(CompilationService) for full and incremental JVM compilation, isolated from jk's " +
        "own classpath. Reads a line-oriented spec, streams NDJSON diagnostics back to jk."

// plugin-api is tiny and dependency-free; vendor just its classes into the thin
// worker jar (not the whole runtime closure — the BTA impl stays external).
val bundledCodec by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // Compile against the stable Build Tools API only. The implementation
    // (kotlin-build-tools-impl + the Kotlin compiler closure) is resolved at
    // runtime — version-matched to the project's Kotlin — and placed on the
    // worker's classpath by jk's launcher. It must NOT be bundled here, so the
    // worker jar stays tiny and the compiler's transitive deps never leak into
    // jk's own classpath.
    compileOnly(libs.kotlin.build.tools.api)
    // Tests drive the worker against the API directly.
    testImplementation(libs.kotlin.build.tools.api)
    // The shared NDJSON protocol codec. compileOnly so it doesn't drag onto the
    // worker's resolved runtime closure; its classes are vendored into the jar
    // via `bundledCodec` below so the codec is present in the worker JVM.
    compileOnly(project(":plugin-api"))
    bundledCodec(project(":plugin-api"))
    testImplementation(project(":plugin-api"))
}

tasks.jar {
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.kotlin.compiler.KotlinCompilerWorker",
                "Implementation-Title" to "jk-kotlin-compiler",
                "Implementation-Version" to project.version)
    }
    dependsOn(bundledCodec)
    from(bundledCodec.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

/**
 * Side-load the freshly-built worker jar into the developer's local jk CAS at
 * {@code ~/.jk/cache/sha256/AA/BB/<rest>}, keyed by its SHA-256 — so a dev
 * {@code jk build} of a Kotlin project finds the worker without setting
 * {@code -Djk.kotlin.worker.jar}. Run after any change to the worker:
 * {@code ./gradlew :kotlin-compiler:installLocalCas}. Mirrors what
 * {@code jk sync} will do once the worker is published to Maven Central.
 */
tasks.register("installLocalCas") {
    description = "Side-load the freshly-built jk-kotlin-compiler jar into ~/.jk/cache/sha256/<hash>"
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
        println("Installed jk-kotlin-compiler ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}
