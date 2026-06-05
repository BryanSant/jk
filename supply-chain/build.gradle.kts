// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk supply chain: deny policy"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
}
