// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    `java-test-fixtures`
}

description = "jk supply chain: audit, sbom, deny, publish"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(libs.bouncycastle.bcpg)
    // The GpgTestFixture in testFixtures uses BC's keypair generators.
    testFixturesImplementation(libs.bouncycastle.bcpg)
    implementation(libs.jackson.databind)
    implementation(libs.sigstore.java)
    implementation(libs.cyclonedx.core.java)
    implementation(libs.spdx.java.library)
}
