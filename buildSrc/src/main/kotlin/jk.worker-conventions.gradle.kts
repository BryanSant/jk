// SPDX-License-Identifier: Apache-2.0

// Shared conventions for jk's child-JVM worker modules (the "runner" plugins).
// Each worker jar is published to the local Maven repo so `jk sync` can pull it
// into the CAS, is launchable as `java -jar` via the shared PluginWorkerMain entry
// point, and ships an `installLocalCas` task that side-loads the freshly-built
// jar into ~/.jk/cache by its SHA-256.
//
// What stays in each worker's build.gradle.kts: its `description`, its
// `dependencies`, and the one thing that genuinely differs — what gets bundled
// into the jar (a fat worker bundles its whole runtime closure; a thin worker
// vendors only the plugin-api codec). The artifactId is always `jk-<projectName>`.

import java.io.File
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

// Coordinates + version must match dev.jkbuild.util.JkVersion.VERSION and the
// dev.jkbuild.worker.WorkerJar registry (artifactId = jk-<projectName>).
group = "dev.jkbuild"
version = "0.1.0-SNAPSHOT"

val workerArtifact = "jk-${project.name}"

publishing {
    publications {
        create<MavenPublication>("worker") {
            artifactId = workerArtifact
            from(components["java"])
        }
    }
}

tasks.jar {
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.plugin.worker.PluginWorkerMain",
                "Implementation-Title" to workerArtifact,
                "Implementation-Version" to project.version)
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // JAR signature files become invalid once deps are merged into the jar.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

// Side-load the freshly-built worker jar into the developer's local jk CAS at
// ~/.jk/cache/sha256/AA/BB/<rest>, keyed by its SHA-256 — so a dev build finds
// the worker without setting -Djk.<x>.worker.jar. Mirrors what `jk sync` does
// once the worker is published to Maven Central.
tasks.register("installLocalCas") {
    description = "Side-load the freshly-built $workerArtifact jar into ~/.jk/cache/sha256/<hash>"
    group = "jk"
    dependsOn(tasks.jar)
    val jarProvider = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(jarProvider)
    val artifact = workerArtifact
    doLast {
        val jar = jarProvider.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val cacheRoot: File = System.getenv("JK_CACHE_DIR")?.let { File(it) }
                ?: System.getenv("JK_HOME")?.let { File(it).resolve("cache") }
                ?: File(System.getProperty("user.home"), ".jk/cache")
        val target = cacheRoot.resolve("sha256")
                .resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4))
                .resolve(hex.substring(4))
        target.parentFile.mkdirs()
        jar.copyTo(target, overwrite = true)
        println("Installed $artifact ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}
