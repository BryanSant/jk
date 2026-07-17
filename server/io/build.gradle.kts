// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk I/O: http, cache, git, repo"

dependencies {
    // The thin client slice (http, forge auth, credential files, Cas read/link surface) — carved
    // out for the slim client (Stage 5); api so :io's own consumers keep seeing those packages.
    api(project(":client-io"))
    implementation(project(":core"))
    // JGit is now in :git-runner (subprocess worker); io no longer needs it.
}
