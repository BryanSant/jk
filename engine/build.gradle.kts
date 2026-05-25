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
    // jk forks a JVM child for `jk test` that runs JUnit's ConsoleLauncher;
    // both jars need to be in the install-dist `lib/` dir so the launcher
    // can locate them and add them to the child's classpath.
    implementation(libs.junit.platform.launcher)
    implementation(libs.junit.platform.console)
    implementation(libs.junit.jupiter)
    // Kotlin support is now subprocess-based — SubprocessKotlincStrategy
    // execs <kotlin-home>/bin/kotlinc from a jk-installed distribution.
    // kotlin-compiler-embeddable (~50 MB) and kotlin-stdlib are no longer
    // on the classpath; the user-provided classpath still gets the
    // appropriate stdlib via the project's declared deps.
}
