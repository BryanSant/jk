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
    ":core",
    ":plugin-api",
    ":io",
    ":resolver",
    ":toolchain",
    ":engine",
    // :supply-chain deleted — PolicyChecker merged into :core
    // :supply-chain-testkit deleted — GpgTestFixture merged into :publish-runner/src/test
    // :image deleted — ImageConfig merged into :core
    // :compat deleted — tool classes → :toolchain; bridge classes → :compat-runner
    ":test-runner",
    ":kotlin-compiler",
    ":java-compiler",
    ":audit-runner",
    ":publish-runner",
    ":image-runner",
    ":compat-runner",
    ":git-runner",
    ":runtime",
    ":host",
    ":cli",
)
