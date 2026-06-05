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
    // JdkInstaller extracts tar.gz archives using MinimalTar (built-in, no library).
    // JdkCatalogClient downloads jdks.json (uncompressed) — no XZ or JSON library needed.
}
