// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk Maven/Gradle compatibility: passthrough and import"

dependencies {
    implementation(project(":core"))
    // ToolInstaller streams over HttpClient via the :io Http wrapper.
    implementation(project(":io"))
    // ToolInstaller extracts tar.gz Maven distributions.
    implementation(libs.commons.compress)
}
