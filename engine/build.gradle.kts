// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk build engine: action graph, compile, test"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    // jk launches JUnit Platform programmatically for jk test — these need
    // to be on the runtime classpath, not just the test classpath.
    implementation(libs.junit.platform.launcher)
    implementation(libs.junit.jupiter)
}
