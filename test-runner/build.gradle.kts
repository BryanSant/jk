// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk-test-runner: child-JVM entry point that drives JUnit Platform and " +
        "streams events back to the parent jk process as NDJSON on stdout."

dependencies {
    // The runner only needs the Launcher API at compile time. Engines and the
    // user's test classes are added to the child classpath at fork time by
    // jk's TestCommand — they don't belong here.
    implementation(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes(
                "Main-Class" to "dev.jkbuild.test.runner.JkRunner",
                "Implementation-Title" to "jk-test-runner",
                "Implementation-Version" to project.version)
    }
}
