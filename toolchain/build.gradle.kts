// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk toolchain: JDK manager, scripts, tool envs"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    // ToolResolver leans on NaiveResolver + EffectivePomBuilder for transitive deps.
    implementation(project(":resolver"))
    // JdkCatalogClient parses the JetBrains JDK feed JSON; DiscoClient
    // (dormant) parses the foojay Disco JSON response.
    implementation(libs.jackson.databind)
    // JdkInstaller streams tar.gz natively instead of forking `tar`.
    implementation(libs.commons.compress)
    // JdkCatalogClient decompresses the JetBrains feed (jdks.json.xz).
    // Pulled in via commons-compress's XZCompressorInputStream at runtime.
    implementation(libs.tukaani.xz)
}
