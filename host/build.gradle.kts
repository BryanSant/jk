// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

description = "jk Workspace Host: the one-shot JVM that owns the scheduler, " +
        "runs the build pipeline, and streams structured events back to the CLI."

group = "dev.jkbuild"
version = "0.1.0-SNAPSHOT"

publishing {
    publications {
        create<MavenPublication>("host") {
            artifactId = "jk-host"
            from(components["java"])
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":plugin-api"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    implementation(project(":engine"))
    // supply-chain/image deleted: moved to :core
    // compat deleted: tool classes moved to :toolchain, bridge classes moved to :compat-runner
    implementation(project(":runtime"))

    testImplementation(libs.jgit)   // test fixtures only
}

// Fat JAR: bundle all runtime deps so the host is launchable as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.host.HostMain",
                "Implementation-Title" to "jk-host",
                "Implementation-Version" to project.version)
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
}

// Side-load into the developer's local jk CAS.
tasks.register("installLocalCas") {
    description = "Side-load the freshly-built jk-host jar into ~/.jk/cache/sha256/<hash>"
    group = "jk"
    dependsOn(tasks.jar)
    val jarProvider = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(jarProvider)
    doLast {
        val jar = jarProvider.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val cacheRoot: File = System.getenv("JK_CACHE_DIR")?.let { File(it) }
                ?: System.getenv("JK_HOME")?.let { File(it).resolve("cache") }
                ?: File(System.getProperty("user.home"), ".jk/cache")
        val target = cacheRoot.resolve("sha256").resolve(hex.substring(0, 2))
                .resolve(hex.substring(2, 4)).resolve(hex.substring(4))
        target.parentFile.mkdirs()
        jar.copyTo(target, overwrite = true)
        println("Installed jk-host ${jar.length()} bytes  sha256: $hex")
    }
}
