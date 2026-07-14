// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk-support: shared filesystem/hashing/XML machinery (PathUtil, Hashing, " +
        "TreeFingerprint, JkDirs, GitUrl, MinimalXml, AtomicWrites). Deliberately NOT part of " +
        "the jk-api contract leaf — IO utilities have no business on a consumer's compile " +
        "classpath. Depends only on the leaf (for the shared executor seam)."

dependencies {
    implementation(project(":jk-api"))
}
