// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk supply-chain testkit: shared test helpers (GpgTestFixture)"

dependencies {
    implementation(libs.bouncycastle.bcpg)
}
