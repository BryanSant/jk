// SPDX-License-Identifier: Apache-2.0

import java.security.MessageDigest

plugins {
    id("jk.java-conventions")
}

description = "jk runtime: the build pipeline + command logic, embeddable without the CLI/TUI"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":resolver"))
    implementation(project(":engine"))
    implementation(project(":toolchain"))
    implementation(project(":compat"))
    implementation(project(":supply-chain"))
    testImplementation(libs.jgit)   // test fixtures only; not in production binary
}

// The jk-kotlin-compiler worker jar isn't embedded anywhere — it's a separate
// process that runtime locates in the local CAS by its SHA-256 (a uniform
// on-disk location that works for both JVM and native-image jk; once the worker
// ships to Maven Central, `jk sync` populates it, and the dev loop side-loads it
// via `:kotlin-compiler:installLocalCas`). KotlinWorkerSetup needs the expected
// hash at runtime: hash the worker jar at build time and emit the hex digest as
// a resource at META-INF/jk-kotlin-compiler-sha256.txt.
val kotlinWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    kotlinWorkerJar(project(":kotlin-compiler"))
}
val writeKotlinWorkerSha by tasks.registering {
    val inputJar = kotlinWorkerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/kotlin-worker-sha/META-INF/jk-kotlin-compiler-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sb.toString())
        }
    }
}
// Same scheme for the jk-java-compiler worker (annotation-processor incrementality).
val javaWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    javaWorkerJar(project(":java-compiler"))
}
val writeJavaWorkerSha by tasks.registering {
    val inputJar = javaWorkerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/java-worker-sha/META-INF/jk-java-compiler-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sb.toString())
        }
    }
}
// Same scheme for the jk-git-runner worker (JGit operations).
val gitWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    gitWorkerJar(project(":git-runner"))
}
val writeGitWorkerSha by tasks.registering {
    val inputJar = gitWorkerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/git-worker-sha/META-INF/jk-git-runner-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) { sb.append(String.format("%02x", b.toInt() and 0xff)) }
        outFile.get().asFile.apply { parentFile.mkdirs(); writeText(sb.toString()) }
    }
}

// Pass the git-runner jar path to tests so GitFetcher can fork the worker.
val testGitWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testGitWorkerJar(project(":git-runner")) }
tasks.withType<Test>().configureEach {
    dependsOn(testGitWorkerJar)
    doFirst { systemProperty("jk.git.worker.jar", testGitWorkerJar.singleFile.absolutePath) }
}

// Same scheme for the jk-image-runner worker (OCI image building via Jib).
val imageWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    imageWorkerJar(project(":image-runner"))
}
val writeImageWorkerSha by tasks.registering {
    val inputJar = imageWorkerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/image-worker-sha/META-INF/jk-image-runner-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) { sb.append(String.format("%02x", b.toInt() and 0xff)) }
        outFile.get().asFile.apply { parentFile.mkdirs(); writeText(sb.toString()) }
    }
}

// Same scheme for the jk-compat-runner worker (import/export/mvn/gradle).
val compatWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    compatWorkerJar(project(":compat-runner"))
}
val writeCompatWorkerSha by tasks.registering {
    val inputJar = compatWorkerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/compat-worker-sha/META-INF/jk-compat-runner-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) { sb.append(String.format("%02x", b.toInt() and 0xff)) }
        outFile.get().asFile.apply { parentFile.mkdirs(); writeText(sb.toString()) }
    }
}

// Same scheme for the jk-publish-runner worker (artifact publishing + signing).
val publishWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    publishWorkerJar(project(":publish-runner"))
}
val writePublishWorkerSha by tasks.registering {
    val inputJar = publishWorkerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/publish-worker-sha/META-INF/jk-publish-runner-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sb.toString())
        }
    }
}

// Same scheme for the jk-audit-runner worker (OSV vulnerability scanning).
val auditWorkerJar by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    auditWorkerJar(project(":audit-runner"))
}
val writeAuditWorkerSha by tasks.registering {
    val inputJar = auditWorkerJar
    inputs.files(inputJar)
    val outFile = layout.buildDirectory.file(
            "generated/resources/audit-worker-sha/META-INF/jk-audit-runner-sha256.txt")
    outputs.file(outFile)
    doLast {
        val jarBytes = inputJar.singleFile.readBytes()
        val digest: ByteArray = MessageDigest.getInstance("SHA-256").digest(jarBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sb.toString())
        }
    }
}
sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/resources/kotlin-worker-sha"))
    resources.srcDir(layout.buildDirectory.dir("generated/resources/java-worker-sha"))
    resources.srcDir(layout.buildDirectory.dir("generated/resources/git-worker-sha"))
    resources.srcDir(layout.buildDirectory.dir("generated/resources/audit-worker-sha"))
    resources.srcDir(layout.buildDirectory.dir("generated/resources/publish-worker-sha"))
    resources.srcDir(layout.buildDirectory.dir("generated/resources/image-worker-sha"))
    resources.srcDir(layout.buildDirectory.dir("generated/resources/compat-worker-sha"))
}
tasks.named("processResources") {
    dependsOn(writeKotlinWorkerSha, writeJavaWorkerSha, writeAuditWorkerSha,
              writePublishWorkerSha, writeImageWorkerSha, writeCompatWorkerSha,
              writeGitWorkerSha)
}
tasks.named("sourcesJar") {
    dependsOn(writeKotlinWorkerSha, writeJavaWorkerSha, writeAuditWorkerSha,
              writePublishWorkerSha, writeImageWorkerSha, writeCompatWorkerSha,
              writeGitWorkerSha)
}
