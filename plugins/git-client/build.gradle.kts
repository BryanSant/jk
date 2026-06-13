// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.worker-conventions")
}

description = "jk-git-runner: child-JVM worker that performs JGit operations " +
        "(clone, fetch, resolve-ref, verify-locked). Isolates JGit from jk's own " +
        "classpath so the main binary carries no git library code."

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    // The plugin SPI + shared host main + NDJSON codec. Bundled into the fat
    // worker jar; the jar's Main-Class is plugin-api's PluginWorkerMain, which
    // ServiceLoader-loads GitPlugin.
    implementation(project(":plugin-api"))
    implementation(libs.jgit)
    testImplementation(libs.jgit)
}

// Fat JAR: bundle the full runtime closure so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
