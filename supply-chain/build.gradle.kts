// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk supply chain: audit, sbom, deny, publish"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(libs.bouncycastle.bcpg)
    implementation(libs.sigstore.java)
    implementation(libs.cyclonedx.core.java)
    implementation(libs.spdx.java.library)
}
