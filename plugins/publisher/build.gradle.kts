// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-publish-runner: child-JVM worker that assembles, signs, and publishes Maven " +
        "artifacts. Isolates BouncyCastle, sigstore-java, and the upload HTTP logic from jk's " +
        "own classpath. Reads a line-oriented spec, streams JSONL progress back to jk."

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":support"))  // Hashing for artifact checksums
    implementation(project(":plugin-api"))  // shared JSONL codec (bundled into the fat jar)
    implementation(libs.bouncycastle.bcpg)
    implementation(libs.sigstore.java)

    // GpgTestFixture moved here from supply-chain-testkit (which is deleted)
    testImplementation(libs.bouncycastle.bcpg)
}

// Fat JAR: bundle the full runtime closure so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
