// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk resolver: PubGrub solver and conflict diagnostics"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
}
