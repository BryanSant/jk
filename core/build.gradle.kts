// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk core foundations: util, event, model, config, lock"

dependencies {
    api(libs.tomlj)
}
