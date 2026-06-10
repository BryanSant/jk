// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk I/O: http, cache, git, repo"

dependencies {
    implementation(project(":core"))
    // JGit is now in :git-runner (subprocess worker); io no longer needs it.
}
