// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk Maven/Gradle compatibility: passthrough and import"

dependencies {
    implementation(project(":core"))
    // ToolInstaller streams over HttpClient via the :io Http wrapper.
    implementation(project(":io"))
    // Tool discovery (find existing SDKMAN/Homebrew/asdf/... installs) and
    // MinimalTar for tar.gz extraction without an external library.
    implementation(project(":toolchain"))
}
