// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-test-runner: child-JVM entry point that drives JUnit Platform and " +
        "streams events back to the parent jk process as JSONL on stdout."

// plugin-api is tiny and dependency-free; vendor just its codec classes into the
// thin runner jar (the JUnit launcher closure must NOT be bundled — jk adds
// version-matched engines + the user's classes at fork time).
val bundledCodec by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(libs.junit.platform.engine)
    compileOnly(project(":plugin-sdk"))
    bundledCodec(project(":plugin-sdk"))
    testImplementation(project(":plugin-sdk"))
}

tasks.jar {
    dependsOn(bundledCodec)
    from(bundledCodec.map { zipTree(it) })
}

// This jar RIDES THE USER'S TEST JVM (the forked test process runs on the project's pinned
// JDK — that's the point of pinning). jk promises a JDK 17+ project floor (requirements.md),
// so this module and its vendored plugin-api codec compile at 17 while the rest of jk stays
// at the engine's language level. JUnit Platform 6's own baseline is 17, matching.
tasks.compileJava {
    options.release.set(17)
}
