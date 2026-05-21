// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk I/O: http, cache, git, repo"

dependencies {
    implementation(project(":core"))
    implementation(libs.jgit)
    implementation(libs.jackson.databind)
}
