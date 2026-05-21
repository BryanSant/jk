// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk core foundations: util, event, model, hocon, lock"

dependencies {
    api(libs.typesafe.config)
    api(libs.tomlj)
    implementation(libs.jackson.databind)
}
