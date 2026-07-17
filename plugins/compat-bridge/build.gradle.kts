// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-compat-runner: child-JVM worker that handles Maven/Gradle import, export, " +
        "and distribution provisioning. Keeps Maven XML parsing code and tool installer " +
        "out of the main jk binary's GraalVM reachable set."

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":toolchain"))
    // compat deleted: mvn/gradle/kotlin bridge + JkBuildRenderer moved into this module
    implementation(project(":plugin-api"))  // shared JSONL codec (bundled into the fat jar)
}

// Fat JAR: bundle the full runtime closure so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
