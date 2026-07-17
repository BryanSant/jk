// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-audit-runner: child-JVM worker that queries the OSV vulnerability API and " +
        "streams JSONL findings back to jk. Isolated from jk's own classpath so Jackson " +
        "and the OSV HTTP client never load in the main jk process."

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-api"))  // shared JSONL codec (bundled into the fat jar)
    implementation(libs.jackson.databind)
}

// Fat JAR: bundle the full runtime closure so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
