// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-shrink: the built-in shrink build plugin's code layer — R8 in --classfile " +
        "mode over the app + runtime closure, packaged as a slim self-contained jar in a " +
        "forked worker JVM over the build-plugin harness. The generic-JVM counterpart of " +
        "the android plugin's release shrinking, sharing the same r8 artifact."

dependencies {
    implementation(project(":plugin-api"))
}

// Fat JAR: bundle the runtime closure (plugin-api + model) so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
