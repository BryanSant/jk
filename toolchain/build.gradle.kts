// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk toolchain: JDK manager, scripts, tool envs"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
}
