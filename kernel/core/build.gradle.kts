// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk core foundations: TOML config parser, lockfile, layout, library catalog, deny policy"

dependencies {
    api(project(":model"))
    api(libs.tomlj)
    // ComparableVersion is the Maven-canonical version comparator (e.g.
    // `1.0-alpha < 1.0-rc < 1.0 < 1.0-sp1`). Used by Versions.compare, rehomed here from
    // :resolver for the slim client (tree/why/library/jdk-list are offline client verbs).
    implementation(libs.maven.artifact)
}
