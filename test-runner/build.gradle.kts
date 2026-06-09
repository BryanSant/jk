// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.worker-conventions")
}

description = "jk-test-runner: child-JVM entry point that drives JUnit Platform and " +
        "streams events back to the parent jk process as NDJSON on stdout."

// plugin-api is tiny and dependency-free; vendor just its codec classes into the
// thin runner jar (the JUnit launcher closure must NOT be bundled — jk adds
// version-matched engines + the user's classes at fork time).
val bundledCodec by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(libs.junit.platform.launcher)
    compileOnly(project(":plugin-api"))
    bundledCodec(project(":plugin-api"))
    testImplementation(project(":plugin-api"))
}

tasks.jar {
    dependsOn(bundledCodec)
    from(bundledCodec.map { zipTree(it) })
}
