// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk core foundations: TOML config parser, lockfile, layout, library catalog, deny policy"

dependencies {
    api(project(":model"))
    api(libs.tomlj)
}
