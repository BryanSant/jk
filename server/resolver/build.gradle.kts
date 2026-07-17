// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk resolver: PubGrub solver and conflict diagnostics"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    // ComparableVersion is the Maven-canonical version comparator (e.g.
    // `1.0-alpha < 1.0-rc < 1.0 < 1.0-sp1`). Used by Versions.compare.
    implementation(libs.maven.artifact)
}
