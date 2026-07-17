// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk client I/O: the thin slice the jk client keeps — plain-JDK http, forge (OAuth " +
        "device-flow) auth, repository credential files, the library-registry catalog client, and " +
        "the content-addressed store's local read/link surface (slim-client Stage 5). No repo " +
        "machinery: POM walking, Maven transports, and fetch pipelines live in :io."

dependencies {
    api(project(":core"))
}
