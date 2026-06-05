// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk OCI image config model (ImageConfig record). " +
        "Jib-core and the actual image builder live in :image-runner."

dependencies {
    implementation(project(":core"))
}
