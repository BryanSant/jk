// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk toolchain (client slice): the complete JDK flow — catalog, installer, registry, " +
        "selection, pointer files — plus the tool/app launcher shims, script headers, exporters, " +
        "and the doctor's local-tool discovery (slim-client Stage 5). Everything here must work " +
        "with no engine running; the resolver-backed compat/import machinery stays in :toolchain."

dependencies {
    api(project(":core"))
    api(project(":client-io"))
}
