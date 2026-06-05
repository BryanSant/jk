// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

group = "dev.jkbuild"
version = "0.1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("worker") {
            artifactId = "jk-publish-runner"
            from(components["java"])
        }
    }
}

description = "jk-publish-runner: child-JVM worker that assembles, signs, and publishes Maven " +
        "artifacts. Isolates BouncyCastle, sigstore-java, and the upload HTTP logic from jk's " +
        "own classpath. Reads a line-oriented spec, streams NDJSON progress back to jk."

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(libs.bouncycastle.bcpg)
    implementation(libs.sigstore.java)

    testImplementation(project(":supply-chain-testkit"))
}

// Fat JAR: bundle all runtime deps so the worker is launchable as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.publish.runner.PublishRunner",
                "Implementation-Title" to "jk-publish-runner",
                "Implementation-Version" to project.version)
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Exclude JAR signature files — they become invalid when deps are merged.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

tasks.register("installLocalCas") {
    description = "Side-load the freshly-built jk-publish-runner jar into ~/.jk/cache/sha256/<hash>"
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
        println("Installed jk-publish-runner ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}
