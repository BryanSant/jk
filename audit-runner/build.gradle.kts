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
            artifactId = "jk-audit-runner"
            from(components["java"])
        }
    }
}

description = "jk-audit-runner: child-JVM worker that queries the OSV vulnerability API and " +
        "streams NDJSON findings back to jk. Isolated from jk's own classpath so Jackson " +
        "and the OSV HTTP client never load in the main jk process."

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))  // shared NDJSON codec (bundled into fat jar)
    implementation(libs.jackson.databind)
}

// Fat JAR: bundle all runtime deps so the worker is launchable as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.plugin.host.PluginHostMain",
                "Implementation-Title" to "jk-audit-runner",
                "Implementation-Version" to project.version)
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Exclude JAR signature files — they become invalid when deps are merged.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

/**
 * Side-load the freshly-built worker jar into the developer's local jk CAS at
 * {@code ~/.jk/cache/sha256/AA/BB/<rest>}, keyed by its SHA-256 — so a dev
 * {@code jk audit} finds the worker without setting {@code -Djk.audit.worker.jar}.
 * Run after any change to the worker: {@code ./gradlew :audit-runner:installLocalCas}.
 * Mirrors what {@code jk sync} will do once the worker is published to Maven Central.
 */
tasks.register("installLocalCas") {
    description = "Side-load the freshly-built jk-audit-runner jar into ~/.jk/cache/sha256/<hash>"
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
        println("Installed jk-audit-runner ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}
