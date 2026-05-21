// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk Maven/Gradle compatibility: passthrough and import"

dependencies {
    implementation(project(":core"))
}
