// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk runtime: the build pipeline + command logic, embeddable without the CLI/TUI"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":resolver"))
    implementation(project(":engine"))
    implementation(project(":toolchain"))
    implementation(project(":compat"))
}
