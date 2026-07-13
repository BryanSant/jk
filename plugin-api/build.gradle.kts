// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk plugin SPI: the stable surface plugins compile against, plus " +
        "the host<->plugin wire protocol codec."

// Dependency-free BY DESIGN — this is the bottom of the contract-leaf pair (see
// kernel/model/build.gradle.kts): its classes are vendored into jk-test-runner and ride
// the user's test JVM on the project's pinned JDK, so this module holds jk's promised
// JDK 17 project floor. model depends on THIS module (for PluginConfig), never the
// reverse.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}
