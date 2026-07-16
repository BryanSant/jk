// SPDX-License-Identifier: Apache-2.0

// Shared conventions for jk's child-JVM worker modules (the "runner" plugins).
// Each worker jar is published to the local Maven repo so `jk sync` can pull it
// into the CAS, is launchable as `java -jar` via the shared PluginMain entry
// point, and ships an `installLocal` task that side-loads the freshly-built
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

// Coordinates + version must match cc.jumpkick.util.JkVersion.VERSION and the
// cc.jumpkick.engine.plugin.PluginJar registry (artifactId = jk-<projectName>).
group = "cc.jumpkick"
version = "0.10.0-SNAPSHOT"

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
                "Main-Class" to "cc.jumpkick.plugin.process.PluginMain",
                "Implementation-Title" to workerArtifact,
                "Implementation-Version" to project.version)
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // JAR signature files become invalid once deps are merged into the jar.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

// Side-load the freshly-built worker jar into the developer's local Maven repo at
// ~/.jk/cache/repos/local/cc/jumpkick/<artifact>/<version>/<artifact>-<version>.jar
// so PluginJar.locate() finds the worker without requiring -Djk.<x>.plugin.jar.
// Also writes a .sha256 sidecar so RepoArtifactStore.locate() sees it as complete.
tasks.register("installLocal") {
    description = "Side-load the freshly-built $workerArtifact jar into ~/.jk/cache/repos/local/ (m2 layout)"
    group = "jk"
    dependsOn(tasks.jar)
    val jarProvider = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(jarProvider)
    val artifact = workerArtifact
    val ver = project.version.toString()
    doLast {
        val jar = jarProvider.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val cacheRoot: File = System.getenv("JK_CACHE_DIR")?.let { File(it) }
                ?: System.getenv("JK_HOME")?.let { File(it).resolve("cache") }
                ?: File(System.getProperty("user.home"), ".jk/cache")
        val repoDir = cacheRoot.resolve("repos/local/cc/jumpkick/$artifact/$ver")
        repoDir.mkdirs()
        val target = repoDir.resolve("$artifact-$ver.jar")
        val sidecar = repoDir.resolve("$artifact-$ver.jar.sha256")
        jar.copyTo(target, overwrite = true)
        sidecar.writeText(hex)
        println("Installed $artifact $ver ${jar.length()} bytes")
        println("  sha256: $hex")
        println("  path:   $target")
    }
}

