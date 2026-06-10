// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.worker-conventions")
}

description = "jk-image-runner: child-JVM worker that builds and pushes OCI images via Jib. " +
        "Isolates Jib-core, Guava, and the Google HTTP stack from jk's own classpath."

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    // image module deleted; ImageConfig moved to :core
    implementation(project(":plugin-api"))  // shared NDJSON codec (bundled into the fat jar)
    implementation(libs.jib.core)
}

// Fat JAR: bundle the full runtime closure so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
