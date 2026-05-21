// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk OCI image builder (Jib-core)"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(libs.jib.core)
}
