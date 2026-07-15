// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-kotlin-compiler: child-JVM worker that drives the Kotlin Build Tools API " +
        "(CompilationService) for full and incremental JVM compilation, isolated from jk's " +
        "own classpath. Reads a line-oriented spec, streams NDJSON diagnostics back to jk."

// plugin-api is tiny and dependency-free; vendor just its codec classes into the
// thin worker jar (not the whole runtime closure — the BTA impl stays external).
val bundledCodec by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // Compile against the stable Build Tools API only. The implementation
    // (kotlin-build-tools-impl + the Kotlin compiler closure) is resolved at
    // runtime — version-matched to the project's Kotlin — and placed on the
    // worker's classpath by jk's launcher. It must NOT be bundled here, so the
    // worker jar stays tiny and the compiler's transitive deps never leak into
    // jk's own classpath.
    compileOnly(libs.kotlin.build.tools.api)
    // Compile-time only: the BTA API's class files carry Kotlin @Deprecated(level = …)
    // annotations whose DeprecationLevel enum lives in kotlin-stdlib. Without it on the
    // compile classpath javac warns "unknown enum constant DeprecationLevel.*". compileOnly
    // keeps stdlib off the worker jar and out of the resolved runtime closure.
    compileOnly(libs.kotlin.stdlib)
    // Tests drive the worker against the API directly.
    testImplementation(libs.kotlin.build.tools.api)
    // The shared NDJSON protocol codec. compileOnly so it doesn't drag onto the
    // worker's resolved runtime closure; its classes are vendored into the jar
    // via `bundledCodec` below so the codec is present in the worker JVM.
    compileOnly(project(":plugin-api"))
    bundledCodec(project(":plugin-api"))
    testImplementation(project(":plugin-api"))
}

tasks.jar {
    dependsOn(bundledCodec)
    from(bundledCodec.map { zipTree(it) })
}
