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
    // Kotlin support: kotlinc lives inside jk so .kt sources compile without
    // an external toolchain. Hefty (~50 MB) but unavoidable for in-process
    // compilation. ProcessBuilder fork is a future option. kotlin-stdlib is
    // separately needed on the user-provided classpath when present —
    // KotlincDriver locates it via reflection on kotlin.KotlinVersion.
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.stdlib)
}
