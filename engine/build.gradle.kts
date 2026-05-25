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
    // JUnit Platform is no longer on engine's runtime classpath — `jk test`
    // forks a child JVM that runs jk-test-runner with the JUnit jars
    // sourced from the user's CAS (injected via LockOrchestrator). All
    // engine needs is jackson for parsing the runner's NDJSON event stream.
    implementation(libs.jackson.databind)
    // Kotlin support is now subprocess-based — SubprocessKotlincStrategy
    // execs <kotlin-home>/bin/kotlinc from a jk-installed distribution.
    // kotlin-compiler-embeddable (~50 MB) and kotlin-stdlib are no longer
    // on the classpath; the user-provided classpath still gets the
    // appropriate stdlib via the project's declared deps.
}
