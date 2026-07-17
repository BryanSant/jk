// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-spring-boot: the built-in Spring Boot build plugin's code layer — the " +
        "Spring AOT step and the boot-jar packager, run in a forked worker JVM over the " +
        "build-plugin harness. The declarative layer (schema, BOM, compiler args, kotlin " +
        "plugins) lives in jk-plugin.toml; this jar carries only the hard 10%. Deliberately " +
        "depends on plugin-api alone: it is the blueprint third-party build plugins copy."

dependencies {
    implementation(project(":plugin-sdk"))
}

// Fat JAR: bundle the runtime closure (plugin-api + model) so the worker runs as `java -jar`.
tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
