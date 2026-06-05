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
    ":io",
    ":resolver",
    ":toolchain",
    ":engine",
    ":supply-chain",
    ":supply-chain-testkit",
    ":image",
    ":compat",
    ":test-runner",
    ":kotlin-compiler",
    ":java-compiler",
    ":audit-runner",
    ":publish-runner",
    ":image-runner",
    ":compat-runner",
    ":git-runner",
    ":runtime",
    ":cli",
)
