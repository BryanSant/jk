// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-java-compiler: child-JVM worker that runs javac in-process via the " +
        "JavacTask API and captures annotation-processing provenance (generated source → " +
        "originating source) for incremental compilation. Depends only on the JDK compiler APIs."

// plugin-api is tiny and dependency-free; vendor just its codec classes into the
// thin worker jar (the JDK compiler APIs the worker uses are part of the JDK).
val bundledCodec by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    compileOnly(project(":plugin-sdk"))
    bundledCodec(project(":plugin-sdk"))
    testImplementation(project(":plugin-sdk"))
}

tasks.jar {
    dependsOn(bundledCodec)
    from(bundledCodec.map { zipTree(it) })
}
