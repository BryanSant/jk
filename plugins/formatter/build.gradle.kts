// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.worker-conventions")
}

description = "jk-formatter: child-JVM worker that formats Java/Kotlin (and, later, " +
        "prettier-backed) sources via the Spotless engine. Only spotless-lib is bundled — " +
        "the underlying formatter impls (palantir-java-format, ktfmt, …) are resolved at " +
        "runtime by jk and handed in, keeping them out of the main jk binary."

dependencies {
    implementation(project(":plugin-api"))
    // The Spotless formatting engine. Zero runtime deps of its own; the actual
    // formatter implementations are loaded at runtime via a Provisioner from
    // jar paths jk resolves and passes in the spec.
    implementation(libs.spotless.lib)
    // spotless-lib needs slf4j-api at runtime (it declares it compileOnly); a
    // no-op binding keeps the worker quiet (we report results via NDJSON).
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.nop)
}

// Fat JAR: bundle the full runtime closure so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
