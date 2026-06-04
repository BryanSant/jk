// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

description = "jk-java-compiler: child-JVM worker that runs javac in-process via the " +
        "JavacTask API and captures annotation-processing provenance (generated source → " +
        "originating source) for incremental compilation. Depends only on the JDK compiler APIs."

// Published to the local Maven repo (`./gradlew publishToMavenLocal`) so `jk sync`
// can pull the worker into the CAS. Coordinates + version must match
// JkWorkerSync.GROUP / artifactId and dev.jkbuild.util.JkVersion.VERSION.
group = "dev.jkbuild"
version = "0.1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("worker") {
            artifactId = "jk-java-compiler"
            from(components["java"])
        }
    }
}

tasks.jar {
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.java.compiler.JavaCompilerWorker",
                "Implementation-Title" to "jk-java-compiler",
                "Implementation-Version" to project.version)
    }
}

/**
 * Side-load the freshly-built worker jar into the developer's local jk CAS at
 * {@code ~/.jk/cache/sha256/AA/BB/<rest>}, so a dev build that uses
 * annotation-processor incrementality finds the worker without
 * {@code -Djk.java.worker.jar}. Run after any change:
 * {@code ./gradlew :java-compiler:installLocalCas}.
 */
tasks.register("installLocalCas") {
    description = "Side-load the freshly-built jk-java-compiler jar into ~/.jk/cache/sha256/<hash>"
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
        println("Installed jk-java-compiler ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}
