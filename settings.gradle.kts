// SPDX-License-Identifier: Apache-2.0

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jk"

include(
    // Kernel — universal capabilities (no plugin deps)
    ":model",
    ":core",
    ":plugin-api",
    ":io",
    ":resolver",
    ":toolchain",
    ":toolchain-jdk",
    ":client-io",
    // Plugins — first-party, shipped with jk
    ":test-runner",
    ":kotlin-compiler",
    ":java-compiler",
    ":auditor",        // was :audit-runner
    ":publisher",      // was :publish-runner
    ":image-builder",  // was :image-runner
    ":compat-bridge",  // was :compat-runner
    ":git-client",     // was :git-runner
    ":formatter",      // jk format — Spotless-wrapped Java/Kotlin formatter
    // Host + CLI
    ":engine-api",
    ":engine",
    ":cli",
    ":cli-engine",
)

// Kernel modules live under kernel/
project(":model").projectDir     = file("kernel/model")
project(":core").projectDir      = file("kernel/core")
project(":io").projectDir        = file("kernel/io")
project(":resolver").projectDir  = file("kernel/resolver")
project(":toolchain").projectDir = file("kernel/toolchain")
project(":toolchain-jdk").projectDir = file("kernel/toolchain-jdk")
project(":client-io").projectDir = file("kernel/client-io")
project(":engine-api").projectDir  = file("kernel/engine-api")
project(":engine").projectDir      = file("kernel/engine")

// Plugin modules live under plugins/
project(":test-runner").projectDir    = file("plugins/test-runner")
project(":kotlin-compiler").projectDir = file("plugins/kotlin-compiler")
project(":java-compiler").projectDir  = file("plugins/java-compiler")
project(":auditor").projectDir        = file("plugins/auditor")
project(":publisher").projectDir      = file("plugins/publisher")
project(":image-builder").projectDir  = file("plugins/image-builder")
project(":compat-bridge").projectDir  = file("plugins/compat-bridge")
project(":git-client").projectDir     = file("plugins/git-client")
project(":formatter").projectDir      = file("plugins/formatter")
